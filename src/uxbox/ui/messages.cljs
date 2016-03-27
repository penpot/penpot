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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce +message+ (atom nil))
(def ^:const +animation-timeout+ 600)

(defn set-timeout!
  [ms callback]
  (js/setTimeout callback ms))

(defn abort-timeout!
  [v]
  (js/clearTimeout v))

(defn error
  ([message] (error message nil))
  ([message {:keys [timeout] :or {timeout 30000}}]
   (when-let [prev-message @+message+]
     (abort-timeout! (:timeout-total prev-message))
     (abort-timeout! (:timeout prev-message)))

   (let [timeout-total (set-timeout! (+ timeout +animation-timeout+)
                                     #(reset! +message+ nil))
         timeout (set-timeout! timeout #(swap! +message+ assoc :state :hide))]
     (reset! +message+ {:type :error
                        :state :normal
                        :timeout-total timeout-total
                        :timeout timeout
                        :content message}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn messages-render
  [own]
  (when-let [message (rum/react +message+)]
    (let [classes (classnames :error (= (:type message) :error)
                              :info (= (:type message) :info)
                              :hide-message (= (:state message) :hide)
                              :quick true)]
      (html
       [:div.message {:class classes}
        [:div.message-body
         [:span.close i/close]
         [:span (:content message)]
         [:div.message-action
          [:a.btn-transparent.btn-small "Accept"]
          [:a.btn-transparent.btn-small "Cancel"]
          ]]]))))

(def ^:const messages
  (mx/component
   {:render messages-render
    :name "messages"
    :mixins [mx/static rum/reactive]}))
