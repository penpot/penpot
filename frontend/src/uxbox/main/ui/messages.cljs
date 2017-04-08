(ns uxbox.main.ui.messages
  (:require [lentes.core :as l]
            [uxbox.main.store :as st]
            [uxbox.util.messages :as uum]
            [rumext.core :as mx :include-macros true]))

(def ^:private message-ref
  (-> (l/key :message)
      (l/derive st/state)))

(mx/defc messages-widget
  {:mixins [mx/static mx/reactive]}
  []
  (let [message (mx/react message-ref)
        on-close #(st/emit! (uum/hide))]
    (uum/messages-widget (assoc message :on-close on-close))))
