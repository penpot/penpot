(ns uxbox.main.ui.messages
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.timers :as ts]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.data :refer [classnames]]))

;; --- Main Component (entry point)

(declare notification)

(def ^:private message-iref
  (-> (l/key :message)
      (l/derive st/state)))

(mf/defc messages
  []
  (let [message (mf/deref message-iref)
        ;; message {:type :error
        ;;          :content "Hello world!"}
        ]
    (when message
      [:& notification {:type (:type message)
                        :status (:status message)
                        :content (:content message)}])))

(mf/defc messages-widget
  []
  (let [message (mf/deref message-iref)
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

