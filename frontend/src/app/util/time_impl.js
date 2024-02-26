import fmt1 from "date-fns/format";
import fmt2 from "date-fns/formatDistanceToNowStrict";

import {arSA} from "date-fns/locale/ar-SA";
import {ca} from "date-fns/locale/ca";
import {de} from "date-fns/locale/de";
import {el} from "date-fns/locale/el";
import {enUS} from "date-fns/locale/en-US";
import {es} from "date-fns/locale/es";
import {faIR} from "date-fns/locale/fa-IR";
import {fr} from "date-fns/locale/fr";
import {he} from "date-fns/locale/he";
import {pt} from "date-fns/locale/pt";
import {ptBR} from "date-fns/locale/pt-BR";
import {ro} from "date-fns/locale/ro";
import {ru} from "date-fns/locale/ru";
import {tr} from "date-fns/locale/tr";
import {zhCN} from "date-fns/locale/zh-CN";
import {nl} from "date-fns/locale/nl";
import {eu} from "date-fns/locale/eu";
import {gl} from "date-fns/locale/gl";
import {hr} from "date-fns/locale/hr";
import {it} from "date-fns/locale/it";
import {nb} from "date-fns/locale/nb";
import {pl} from "date-fns/locale/pl";
import {id} from "date-fns/locale/id";
import {uk} from "date-fns/locale/uk";
import {cs} from "date-fns/locale/cs";
import {lv} from "date-fns/locale/lv";
import {ko} from "date-fns/locale/ko";
import {ja} from "date-fns/locale/ja";

export const locales = {
  "ar": arSA,
  "ca": ca,
  "de": de,
  "el": el,
  "en": enUS,
  "en_us": enUS,
  "es": es,
  "es_es": es,
  "fa": faIR,
  "fa_ir": faIR,
  "fr": fr,
  "he": he,
  "pt": pt,
  "pt_pt": pt,
  "pt_br": ptBR,
  "ro": ro,
  "ru": ru,
  "tr": tr,
  "zh_cn": zhCN,
  "nl": nl,
  "eu": eu,
  "gl": gl,
  "hr": hr,
  "it": it,
  "nb": nb,
  "nb_no": nb,
  "pl": pl,
  "id": id,
  "uk": uk,
  "cs": cs,
  "lv": lv,
  "ko": ko,
  "ja": ja,
  "ja_jp": ja,
};

export const format = fmt1.format;
export const format_distance_to_now = fmt2.formatDistanceToNowStrict;
