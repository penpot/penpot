;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.styles.panels.variants-panel
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.variant :as cfv]
   [app.common.types.component :as ctc]
   [app.main.ui.inspect.styles.rows.properties-row :refer [properties-row*]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc variants-panel*
  [{:keys [component objects shape data]}]
  (let [is-container?   (ctc/is-variant-container? shape)
        properties      (mf/with-memo [objects shape]
                          (if is-container?
                            (->> (cfv/extract-properties-values data objects (:id shape))
                                 (map #(update % :value (partial str/join ", "))))
                            (->> (:variant-properties component)
                                 (map #(update % :value (fn [v] (if (str/blank? v) "--" v)))))))]
    [:div {:class (stl/css :variants-panel)}
     (for [property properties]
       [:> properties-row* {:key (dm/str "variant-property-" property)
                            :term (:name property)
                            :detail (:value property)}])]))
