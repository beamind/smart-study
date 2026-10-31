import Cropper from 'cropperjs';
import 'cropperjs/dist/cropper.css';
import { RotateCw } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { toJpeg } from '../../services/photo';
import { Page } from '../../ui/components';

/** Crop and rotate a photo before recognition; the result is a JPEG of at most 1280 px. */
export function CropPhoto({ image, title, hint = '拖动方框，只框住要听写的词语，识别更准确。', onDone, onCancel }: {
  image: string; title: string; hint?: string; onDone(jpeg: string): void; onCancel(): void;
}) {
  const imageRef = useRef<HTMLImageElement>(null);
  const cropper = useRef<Cropper | null>(null);

  useEffect(() => {
    const element = imageRef.current!;
    cropper.current = new Cropper(element, {
      viewMode: 1, autoCropArea: 0.9, background: false, responsive: true, zoomOnWheel: false, toggleDragModeOnDblclick: false,
    });
    return () => { cropper.current?.destroy(); cropper.current = null; };
  }, [image]);

  const finish = () => {
    const canvas = cropper.current?.getCroppedCanvas({ maxWidth: 4096, maxHeight: 4096, fillColor: '#fff', imageSmoothingQuality: 'high' });
    if (canvas) onDone(toJpeg(canvas));
  };

  return (
    <Page title={`${title} · 选择范围`} footer={<>
      <button className="button grow" onClick={onCancel}>取消</button>
      <button className="button" aria-label="旋转" onClick={() => cropper.current?.rotate(90)}><RotateCw size={20} />旋转</button>
      <button className="button primary grow" onClick={finish}>识别</button>
    </>}>
      <p className="muted small">{hint}</p>
      <div className="crop-area"><img ref={imageRef} src={image} alt="要识别的照片" /></div>
    </Page>
  );
}
