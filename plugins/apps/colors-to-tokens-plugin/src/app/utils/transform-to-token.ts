import { LibraryColor } from '@penpot/plugin-types';
import { TokenStructure } from '../../model';

function transformToRgba({
  color,
  opacity,
}: Required<Pick<LibraryColor, 'color' | 'opacity'>>) {
  color = color.slice(1);

  const r = parseInt(color.substring(0, 2), 16);
  const g = parseInt(color.substring(2, 4), 16);
  const b = parseInt(color.substring(4, 6), 16);

  return `rgba(${r}, ${g}, ${b}, ${opacity})`;
}

interface Color extends LibraryColor {
  color: string;
}

export function transformToToken(colors: LibraryColor[]) {
  const result: TokenStructure = {};

  colors
    .filter((data): data is Color => !!data.color)
    .forEach((data) => {
      const currentOpacity = data.opacity ?? 1;
      const value =
        currentOpacity === 1
          ? data.color
          : transformToRgba({
              opacity: currentOpacity,
              color: data.color,
            });

      const names: string[] = data.name.replace(/[#{}$]/g, '').split(' ');
      const key: string =
        data.path.replace(' \\/ ', '/').replace(/ /g, '') || 'global';

      if (!result[key]) {
        result[key] = {};
      }

      const props = [key, ...names];
      let acc = result;

      props.forEach((prop, index) => {
        if (!acc[prop]) {
          acc[prop] = {};
        }

        if (index === props.length - 1) {
          let propIndex = 1;
          const initialProp = prop;

          while (acc[prop]?.$value) {
            prop = `${initialProp}${propIndex}`;
            propIndex++;
          }

          acc[prop] = {
            $value: value,
            $type: 'color',
          };
        }

        acc = acc[prop] as TokenStructure;
      });
    });

  return result;
}
