/**
 * Copy text to the clipboard. Uses the Clipboard API (the app's WebView and PC browsers have it), and the older
 * copy command where that is refused. False when neither works; the text is on screen then, so it can be typed.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch { /* not allowed here: try the copy command */ }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  try {
    area.select();
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    area.remove();
  }
}
