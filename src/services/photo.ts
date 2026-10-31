import { Capacitor } from '@capacitor/core';
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';

/**
 * Taking or choosing a photo. Android: the system camera / photo picker through @capacitor/camera.
 * PC browser: a file chooser (the "camera" button opens it too, since most PCs have no suitable camera).
 * Returns a data URL, or null when the user cancelled.
 */
export type PhotoSource = 'camera' | 'gallery';

export async function pickPhoto(source: PhotoSource): Promise<string | null> {
  if (Capacitor.isNativePlatform()) {
    try {
      const photo = await Camera.getPhoto({
        source: source === 'camera' ? CameraSource.Camera : CameraSource.Photos,
        resultType: CameraResultType.DataUrl,
        quality: 90,
        width: 2400,
        correctOrientation: true,
      });
      return photo.dataUrl ?? null;
    } catch (error) {
      if (/cancel/i.test(String((error as Error).message ?? error))) return null;
      throw error;
    }
  }
  return chooseFile();
}

function chooseFile(): Promise<string | null> {
  return new Promise((resolve, reject) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.style.display = 'none';
    document.body.append(input);
    const done = () => input.remove();
    input.addEventListener('change', () => {
      const file = input.files?.[0];
      done();
      if (!file) { resolve(null); return; }
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(new Error('读取图片失败'));
      reader.readAsDataURL(file);
    });
    input.addEventListener('cancel', () => { done(); resolve(null); });
    input.click();
  });
}

/** Scale so the longer side is at most `maxSide` and encode as JPEG (about 1,400 tokens per photo). */
export function toJpeg(canvas: HTMLCanvasElement, maxSide = 1280, quality = 0.8): string {
  const scale = Math.min(1, maxSide / Math.max(canvas.width, canvas.height));
  if (scale === 1) return canvas.toDataURL('image/jpeg', quality);
  const out = document.createElement('canvas');
  out.width = Math.round(canvas.width * scale);
  out.height = Math.round(canvas.height * scale);
  const context = out.getContext('2d')!;
  context.imageSmoothingQuality = 'high';
  context.drawImage(canvas, 0, 0, out.width, out.height);
  return out.toDataURL('image/jpeg', quality);
}
