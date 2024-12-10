(ns app.main.ui.ds.foundations.utilities.token.token-status
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [collect-icons]]
   [rumext.v2 :as mf]))

(def ^:icon-id token-status-partial "token-status-partial")
(def ^:icon-id token-status-full "token-status-full")
(def ^:icon-id token-status-non-applied "token-status-non-applied")

(def token-status-list "A collection of all status" (collect-icons))

(def ^:private schema:token-status-icon
  [:map
   [:class {:optional true} :string]
   [:id [:and :string [:fn #(contains? token-status-list %)]]]])

(mf/defc token-status-icon*
  {::mf/props :obj
   ::mf/schema schema:token-status-icon}
  [{:keys [id class] :rest props}]
  (let [class (dm/str (or class "") " " (stl/css :token-icon))
        props (mf/spread-props props {:class class :width "14px" :height "14px"})
        offset 0]
    [:> "svg" props
     [:use {:href (dm/str "#icon-" id) :width "14px" :height "14px" :x offset :y offset}]]))
