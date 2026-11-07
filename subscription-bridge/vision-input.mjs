/** 한 장의 작은 JPEG만 허용한다. 외부 URL·파일 ID·다중 이미지·임의 도구 입력은 거절한다. */
export const MAX_REQUEST_BYTES = 262_144;
export function validVisionInput(input) {
  if (!Array.isArray(input) || input.length !== 1) return false;
  const message = input[0];
  if (!message || message.role !== 'user' || Object.keys(message).some(k => !['role','content'].includes(k)) ||
      !Array.isArray(message.content) || message.content.length !== 2) return false;
  const [text, image] = message.content;
  if (text?.type !== 'input_text' || Object.keys(text).some(k => !['type','text'].includes(k)) ||
      typeof text.text !== 'string' || !text.text || text.text.length > 60_000 ||
      image?.type !== 'input_image' || image.detail !== 'high' ||
      Object.keys(image).some(k => !['type','image_url','detail'].includes(k)) ||
      typeof image.image_url !== 'string' || !/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(image.image_url)) return false;
  const base64 = image.image_url.slice(23), bytes = Buffer.from(base64, 'base64');
  if (bytes.toString('base64') !== base64 || bytes.length > 122_880 || bytes.length < 16 || bytes.readUInt16BE(0) !== 0xffd8) return false;
  // JPEG SOF: 포맷 이름만 JPEG인 큰 이미지로 제한을 우회하지 못하게 실제 크기를 검사한다.
  let offset = 2;
  while (offset + 4 <= bytes.length) {
    if (bytes[offset++] !== 0xff) return false;
    while (bytes[offset] === 0xff) offset++;
    if (offset + 3 > bytes.length) return false;
    const marker = bytes[offset++];
    if (marker === 0xda || marker === 0xd9) return false;
    const length = bytes.readUInt16BE(offset);
    if (length < 2 || offset + length > bytes.length) return false;
    if ([0xc0,0xc1,0xc2].includes(marker)) {
      if (length < 8) return false;
      const height = bytes.readUInt16BE(offset+3), width = bytes.readUInt16BE(offset+5);
      return width > 0 && height > 0 && width <= 1280 && height <= 1280;
    }
    offset += length;
  }
  return false;
}
