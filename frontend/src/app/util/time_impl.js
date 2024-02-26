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
import {ptBR} from "date-fns/locale/pt-BR";
import {ro} from "date-fns/locale/ro";
import {ru} from "date-fns/locale/ru";
import {tr} from "date-fns/locale/tr";
import {zhCN} from "date-fns/locale/zh-CN";

export const locales = {
  "ar": arSA,
  "ca": ca,
  "de": de,
  "el": el,
  "en": enUS,
  "es": es,
  "fa": faIR,
  "fr": fr,
  "he": he,
  "pt_br": ptBR,
  "ro": ro,
  "ru": ru,
  "tr": tr,
  "zh_cn": zhCN
};

export const format = fmt1.format;
export const format_distance_to_now = fmt2.formatDistanceToNowStrict;
