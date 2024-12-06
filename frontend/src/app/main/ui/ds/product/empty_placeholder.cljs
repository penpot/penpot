;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.empty-placeholder
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [rumext.v2 :as mf]))

(def ^:private schema:empty-placeholder
  [:map
   [:class {:optional true} :string]
   [:title :string]
   [:subtitle {:optional true} [:maybe :string]]
   [:type {:optional true} [:maybe [:enum 1 2]]]])

(mf/defc empty-placeholder*
  {::mf/props :obj
   ::mf/schema schema:empty-placeholder}
  [{:keys [class title subtitle type children] :rest props}]

  (let [class (dm/str class " " (stl/css :empty-placeholder))
        props (mf/spread-props props {:class class :data-testid "empty-placeholder"})
        type  (or type 1)
        decoration-type (dm/str "empty-placeholder-" (str type))]
    [:> "div" props
     [:> raw-svg* {:id (dm/str decoration-type "-left") :class (stl/css :svg-decor)}]
     [:div {:class (stl/css :text-wrapper)}
      [:> text* {:as "span" :typography t/title-medium :class (stl/css :placeholder-title)} title]
      (when subtitle
        [:> text* {:as "span" :typography t/body-large} subtitle])
      children]
     [:> raw-svg* {:id (dm/str decoration-type "-right") :class (stl/css :svg-decor)}]]))