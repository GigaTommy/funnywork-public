// The brand theme follows appearance; explicit custom colors remain supported.
export const handleThemeStyle = (theme: string) => {
  const dark = document.documentElement.classList.contains('dark');
  const neutral = ['#292724', '#3b82f6'].includes(theme.toLowerCase());
  const primary = neutral ? (dark ? '#eeeae2' : '#292724') : theme;
  const background = dark ? '#201f1d' : '#fffdfa';
  const blend = (amount: number) => {
    const color = hexToRgb(primary), base = hexToRgb(background);
    return rgbToHex(...color.map((value, index) => String(Math.round(Number(value) * (1 - amount) + Number(base[index]) * amount))) as [string,string,string]);
  };
  const style = document.documentElement.style;
  style.setProperty('--el-color-primary', primary);
  style.setProperty('--fw-button-text', dark && neutral ? '#24221f' : '#ffffff');
  for (let i = 1; i <= 9; i++) {
    style.setProperty(`--el-color-primary-light-${i}`, blend(i / 10));
    style.setProperty(`--el-color-primary-dark-${i}`, dark ? getLightColor(primary, i / 10) : getDarkColor(primary, i / 10));
  }
};

// hex颜色转rgb颜色
export const hexToRgb = (str: string): string[] => {
  str = str.replace('#', '');
  const hexs = str.match(/../g);
  for (let i = 0; i < 3; i++) {
    if (hexs) {
      hexs[i] = String(parseInt(hexs[i], 16));
    }
  }
  return hexs ? hexs : [];
};

// rgb颜色转Hex颜色
export const rgbToHex = (r: string, g: string, b: string) => {
  const hexs = [Number(r).toString(16), Number(g).toString(16), Number(b).toString(16)];
  for (let i = 0; i < 3; i++) {
    if (hexs[i].length == 1) {
      hexs[i] = `0${hexs[i]}`;
    }
  }
  return `#${hexs.join('')}`;
};

// 变浅颜色值
export const getLightColor = (color: string, level: number) => {
  const rgb = hexToRgb(color);
  for (let i = 0; i < 3; i++) {
    const s = (255 - Number(rgb[i])) * level + Number(rgb[i]);
    rgb[i] = String(Math.floor(s));
  }
  return rgbToHex(rgb[0], rgb[1], rgb[2]);
};

// 变深颜色值
export const getDarkColor = (color: string, level: number) => {
  const rgb = hexToRgb(color);
  for (let i = 0; i < 3; i++) {
    rgb[i] = String(Math.floor(Number(rgb[i]) * (1 - level)));
  }
  return rgbToHex(rgb[0], rgb[1], rgb[2]);
};
