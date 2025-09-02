(ns app.main.ui.inspect.styles.properties-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:properties-row
  [:map
   [:term :string]
   [:detail :string]
   [:copiable {:optional true} :boolean]])

(mf/defc properties-row*
  {::mf/schema schema:properties-row}
  [{:keys [term detail copiable]}]
  (let [copiable? (or copiable false)
        detail? (not (or (nil? detail) (str/blank? detail)))
        detail (if detail? detail "-")
        copy-attr
        (mf/use-fn
         (fn []
           (wapi/write-to-clipboard (str term ": " detail))))]
    [:dl {:class (stl/css :property-row)}
     [:dt {:class (stl/css :property-term)} term]
     [:dd {:class (stl/css :property-detail)}
      (if (and copiable? detail?)
        [:button {:class (stl/css :property-detail-copiable)
                  :on-click copy-attr} detail]
        detail)]]))
