;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.tag
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

(def tones
  #{"neutral" "accent" "info" "warning" "error" "success" "purple"})

(def ^:private schema:tag
  [:map
   [:class {:optional true} [:maybe :string]]
   [:icon {:optional true} [:maybe [:and :string [:fn #(contains? i/icon-list %)]]]]
   [:tone {:optional true} [:maybe (into [:enum] tones)]]
   [:variant {:optional true} [:maybe [:enum "label" "pill"]]]])

(mf/defc tag*
  "A short, static status word. The `label` variant is a small code-font
  tag that names what something is (Board, Verified, Clash);
  the `pill` variant is a rounded word or count that sits at the end of a
  row (Choose, Decided, 3 edits). The tone colours both; `icon` goes
  before the text."
  {::mf/schema schema:tag}
  [{:keys [class icon tone variant children] :rest props}]
  (let [tone    (d/nilv tone "neutral")
        variant (d/nilv variant "label")
        props   (mf/spread-props props
                                 {:class [class (stl/css-case :tag true
                                                              :is-pill (= variant "pill")
                                                              :tone-accent (= tone "accent")
                                                              :tone-info (= tone "info")
                                                              :tone-warning (= tone "warning")
                                                              :tone-error (= tone "error")
                                                              :tone-success (= tone "success")
                                                              :tone-purple (= tone "purple"))]})]
    [:> :span props
     (when icon
       [:> icon* {:icon-id icon :size "s"}])
     children]))
