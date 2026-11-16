;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.language-profile-menu-test
  (:require
   ["react-dom/server" :as rds]
   [app.main.ui.workspace.main-menu :as main-menu]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- render
  [language disabled]
  (rds/renderToStaticMarkup
   (mf/element @#'main-menu/language-profile-menu*
               (mf/props {:id "menu-language"
                          :label "Language profile"
                          :language language
                          :disabled disabled
                          :on-select identity
                          :on-close-all identity}))))

(t/deftest language-profile-item-shows-the-current-profile
  (t/is (str/includes? (render "ja" false)
                       "workspace.options.text-options.language-japanese"))
  (t/is (str/includes? (render nil false)
                       "workspace.header.menu.language-none")))

(t/deftest disabled-language-profile-item-does-not-open
  (let [markup (render "ko" true)]
    (t/is (str/includes? markup "aria-disabled=\"true\""))
    (t/is (str/includes? markup "workspace.options.text-options.language-korean"))
    (t/is (not (str/includes? markup "role=\"menu\""))
          "the profile list stays closed")))
