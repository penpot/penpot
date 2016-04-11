(ns uxbox.ui.messages
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [promesa.core :as p]
            [cuerdas.core :as str]
            [uxbox.rstore :as rs]
            [uxbox.ui.icons :as i]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.data :refer (classnames)]
            [uxbox.util.dom :as dom]))

;; --- Constants

(defonce +message+ (atom nil))

(def ^:const +animation-timeout+ 600)

;; --- Helpers

(defn set-timeout!
  [ms callback]
  (js/setTimeout callback ms))

(defn abort-timeout!
  [v]
  (when v
    (js/clearTimeout v)))

;; --- Public Api

(defn- clean-prev-msgstate!
  [message]
  (let [type (namespace (:type message))]
    (case type
      "notification"
      (do
        (abort-timeout! (:tsem-main message))
        (abort-timeout! (:tsem message)))

      "dialog"
      (abort-timeout! (:tsem message)))))

(defn error
  ([message] (error message nil))
  ([message {:keys [timeout] :or {timeout 6000}}]
   (when-let [prev @+message+]
     (clean-prev-msgstate! prev))
   (let [timeout' (+ timeout +animation-timeout+)
         tsem-main (set-timeout! timeout' #(reset! +message+ nil))
         tsem (set-timeout! timeout #(swap! +message+ assoc :state :hide))]
     (reset! +message+ {:type :notification/error
                        :state :normal
                        :tsem-main tsem-main
                        :tsem tsem
                        :content message}))))

(defn info
  ([message] (info message nil))
  ([message {:keys [timeout] :or {timeout 6000}}]
   (when-let [prev @+message+]
     (clean-prev-msgstate! prev))
   (let [timeout' (+ timeout +animation-timeout+)
         tsem-main (set-timeout! timeout' #(reset! +message+ nil))
         tsem (set-timeout! timeout #(swap! +message+ assoc :state :hide))]
     (reset! +message+ {:type :notification/info
                        :state :normal
                        :tsem-main tsem-main
                        :tsem tsem
                        :content message}))))

(defn dialog
  [& {:keys [message on-accept on-cancel]
      :or {on-cancel (constantly nil)}
      :as opts}]
  {:pre [(ifn? on-accept)
         (string? message)]}
   (when-let [prev @+message+]
     (clean-prev-msgstate! prev))
  (reset! +message+ {:type :dialog/simple
                     :state :normal
                     :content message
                     :on-accept on-accept
                     :on-cancel on-cancel}))
(defn close
  []
  (when @+message+
    (let [timeout +animation-timeout+
          tsem (set-timeout! timeout #(reset! +message+ nil))]
      (swap! +message+ assoc
             :state :hide
             :tsem tsem))))

;; --- Notification Component

(defn notification-render
  [own message]
  (let [msgtype (name (:type message))
        classes (classnames :error (= msgtype "error")
                            :info (= msgtype "info")
                            :hide-message (= (:state message) :hide)
                            :quick true)]
    (html
     [:div.message {:class classes}
      [:div.message-body
       [:span.close i/close]
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
                            :hide-message (= (:state message) :hide))
        local (:rum/local own)]
    (letfn [(accept [event]
              (dom/prevent-default event)
              (close)
              (on-accept))
            (cancel [event]
              (dom/prevent-default event)
              (close)
              (when on-cancel
                (on-cancel)))]
      (html
       [:div.message {:class classes}
        [:div.message-body
         [:span.close i/close]
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
  (when-let [message (rum/react +message+)]
    (case (namespace (:type message))
      "notification" (notification-box message)
      "dialog"       (dialog-box message)
      (throw (ex-info "Invalid message type" message)))))

(def ^:const messages
  (mx/component
   {:render messages-render
    :name "messages"
    :mixins [mx/static rum/reactive]}))
