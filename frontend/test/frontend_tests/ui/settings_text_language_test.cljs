;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.settings-text-language-test
  (:require
   ["react-dom/server" :as rds]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.settings.options :as options]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- render
  [language]
  (with-redefs [hooks/use-portal-container (fn ([] nil) ([_] nil))]
    (rds/renderToStaticMarkup
     (mf/element @#'options/text-language-settings* (mf/props {:language language})))))

(t/deftest profile-text-language-shows-the-chosen-language
  (t/is (str/includes? (render nil) "dashboard.text-language.none"))
  (t/is (str/includes? (render "zh-Hant-TW") "workspace.options.text-options.language-chinese-variant"))
  (t/is (str/includes? (render "ja") "workspace.options.text-options.language-japanese")))
