;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.buttons.link-button
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

(def ^:private schema:link-button
  [:map
   [:class {:optional true} [:maybe :string]]
   [:icon {:optional true} [:maybe [:and :string [:fn #(contains? i/icon-list %)]]]]
   [:tone {:optional true} [:maybe [:enum "accent" "info" "neutral"]]]])

(mf/defc link-button*
  "An action that reads as a link inside a line of text: no box, underlined
  on hover. `icon` goes before the text."
  {::mf/schema schema:link-button}
  [{:keys [class icon tone children] :rest props}]
  (let [tone  (d/nilv tone "accent")
        props (mf/spread-props props
                               {:class [class (stl/css-case :link-button true
                                                            :tone-info (= tone "info")
                                                            :tone-neutral (= tone "neutral"))]
                                :type "button"})]
    [:> :button props
     (when icon
       [:> icon* {:icon-id icon :size "s"}])
     children]))
