;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.cta
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [rumext.v2 :as mf]))

(def ^:private schema:cta
  [:map
   [:class {:optional true} :string]
   [:title :string]])

(mf/defc cta*
  {::mf/props :obj
   ::mf/schema schema:cta}
  [{:keys [class title children] :rest props}]

  (let [class (d/append-class class (stl/css :cta))
        props (mf/spread-props props {:class class :data-testid "cta"})]
    [:> "div" props
     [:div {:class (stl/css :cta-title)}
      [:> text* {:as "span" :typography t/headline-small :class (stl/css :placeholder-title)} title]]
     [:div {:class (stl/css :cta-message)}
      children]]))
