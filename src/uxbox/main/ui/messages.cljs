(ns uxbox.main.ui.messages
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [promesa.core :as p]
            [lentes.core :as l]
            [uxbox.main.state :as st]
            [uxbox.main.data.messages :as udm]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx]
            [uxbox.util.data :refer (classnames)]
            [uxbox.util.dom :as dom]))

;; --- Lenses

(def ^:const ^:private message-l
  (-> (l/key :message)
      (l/derive st/state)))

;; --- Notification Component

(defn notification-render
  [own {:keys [type] :as message}]
  (let [classes (classnames :error (= type :error)
                            :info (= type :info)
                            :hide-message (= (:state message) :hide)
                            :quick true)
        close #(udm/close!)]
    (html
     [:div.message {:class classes}
      [:div.message-body
       [:span.close {:on-clock close}
        i/close]
       [:span (:content message)]]])))

(def ^:private notification-box
  (mx/component
   {:render notification-render
    :name "notification"
    :mixins [mx/static]}))

;; --- Dialog Component

(defn dialog-render
  [own {:keys [on-accept on-cancel] :as message}]
  (let [classes (classnames :info true
                            :hide-message (= (:state message) :hide))]
    (letfn [(accept [event]
              (dom/prevent-default event)
              (on-accept)
              (p/schedule 0 udm/close!))

            (cancel [event]
              (dom/prevent-default event)
              (when on-cancel
                (on-cancel))
              (p/schedule 0 udm/close!))]
      (html
       [:div.message {:class classes}
        [:div.message-body
         [:span.close {:on-click cancel} i/close]
         [:span (:content message)]
         [:div.message-action
          [:a.btn-transparent.btn-small
           {:on-click accept}
           "Accept"]
          [:a.btn-transparent.btn-small
           {:on-click cancel}
           "Cancel"]]]]))))

(def ^:private dialog-box
  (mx/component
   {:render dialog-render
    :name "dialog"
    :mixins [mx/static]}))

;; --- Main Component (entry point)

(defn messages-render
  [own]
  (let [message (rum/react message-l)]
    (case (:type message)
      :error (notification-box message)
      :info (notification-box message)
      :dialog (dialog-box message)
      nil)))

(def ^:const messages
  (mx/component
   {:render messages-render
    :name "messages"
    :mixins [mx/static rum/reactive]}))
