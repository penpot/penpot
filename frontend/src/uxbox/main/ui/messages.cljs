(ns uxbox.main.ui.messages
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.store :as st]
   [uxbox.util.messages :as um]))

(def ^:private message-iref
  (-> (l/key :message)
      (l/derive st/state)))

(mf/defc messages-widget
  []
  (let [message (mf/deref message-iref)
        on-close #(st/emit! (um/hide))]
    [:& um/messages-widget {:message message
                            :on-close on-close}]))
