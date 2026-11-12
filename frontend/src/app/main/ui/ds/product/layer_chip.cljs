;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.product.layer-chip
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

(def ^:private schema:layer-chip
  [:map
   [:class {:optional true} [:maybe :string]]
   [:label :string]
   [:icon {:optional true} [:maybe [:and :string [:fn #(contains? i/icon-list %)]]]]
   [:is-lost {:optional true} [:maybe :boolean]]
   [:is-struck {:optional true} [:maybe :boolean]]])

(mf/defc layer-chip*
  "A chip naming a layer, or what a comment points at. A lost layer reads
  in the warning tone, struck through when `is-struck`."
  {::mf/schema schema:layer-chip}
  [{:keys [class label icon is-lost is-struck] :rest props}]
  (let [props (mf/spread-props props
                               {:class [class (stl/css-case :layer-chip true
                                                            :is-lost is-lost)]})]
    [:> :span props
     [:> icon* {:icon-id (if ^boolean is-lost i/triangle-alert (or icon i/board)) :size "s"}]
     [:span {:class (stl/css-case :label true
                                  :is-struck is-struck)}
      label]]))
