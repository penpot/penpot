(ns uxbox.main.ui.messages
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.timers :as ts]))

;; --- Main Component (entry point)

(declare notification)

(mf/defc messages
  []
  (let [message (mf/deref refs/message)]
    (when message
      [:& notification {:type (:type message)
                        :status (:status message)
                        :content (:content message)}])))

(mf/defc messages-widget
  []
  (let [message (mf/deref refs/message)
        message {:type :error
                 :content "Hello world!"}]

    [:& notification {:type (:type message)
                      :status (:status message)
                      :content (:content message)}]))

;; --- Notification Component

(mf/defc notification
  [{:keys [type status content] :as props}]
  (let [on-close #(st/emit! dm/hide)
        klass (classnames
               :error (= type :error)
               :info (= type :info)
               :hide-message (= status :hide)
               :success (= type :success)
               :quick false)]
    [:div.message {:class klass}
     [:a.close-button {:on-click on-close} i/close]
     [:div.message-content
      [:span content]]]))

