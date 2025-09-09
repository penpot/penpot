(ns app.main.ui.inspect.styles.property-detail-copiable
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:property-detail-copiable
  [:map
   [:detail :string]
   [:token {:optional true} :any] ;; resolved token object
   [:copied :boolean]
   [:on-click fn?]])

(mf/defc property-detail-copiable*
  {::mf/schema schema:property-detail-copiable}
  [{:keys [detail token copied on-click]}]
  [:button {:class (stl/css-case :property-detail-copiable true
                                 :property-detail-copied copied)
            :on-click on-click}
   (if token
     [:span {:class (stl/css :property-detail-text :property-detail-text-token)} (:name token)]
     [:span {:class (stl/css :property-detail-text)} detail])
   [:> icon* {:class (stl/css :property-detail-icon)
              :icon-id (if copied i/tick i/clipboard)
              :size "s"
              :aria-label (tr "inspect.tabs.styles.panel.copy-to-clipboard")}]])


