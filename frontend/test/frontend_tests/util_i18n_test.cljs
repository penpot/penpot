;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-i18n-test
  (:require
   [app.config :as cf]
   [app.util.i18n :as i18n]
   [cljs.test :as t]))

(defn- detect
  [language]
  (#'i18n/autodetect language))

(t/deftest traditional-chinese-browser-languages
  (doseq [language ["zh-TW" "zh-HK" "zh-MO" "zh-Hant" "zh-Hant-TW" "zh-Hant-HK"]]
    (t/is (= "zh_hant" (detect language)) language)))

(t/deftest simplified-chinese-browser-languages
  (doseq [language ["zh" "zh-CN" "zh-SG" "zh-Hans" "zh-Hans-CN"]]
    (t/is (= "zh_cn" (detect language)) language)))

(t/deftest stored-locales-still-resolve
  (doseq [{:keys [value]} i18n/supported-locales]
    (t/is (= value (detect value)) value)))

(t/deftest region-falls-back-to-language
  (t/is (= "en" (detect "en-US")))
  (t/is (= "pt_br" (detect "pt-BR")))
  (t/is (= "fr" (detect "fr-BE")))
  (t/is (= "ja" (detect "ja-JP")))
  (t/is (= cf/default-language (detect "xx-YY"))))
