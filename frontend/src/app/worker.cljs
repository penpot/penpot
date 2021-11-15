;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker
  (:require
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.worker.export]
   [app.worker.impl :as impl]
   [app.worker.import]
   [app.worker.selection]
   [app.worker.snaps]
   [app.worker.thumbnails]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [promesa.core :as p]))

;; --- Messages Handling

(s/def ::cmd keyword?)

(s/def ::payload
  (s/keys :req-un [::cmd]))

(s/def ::sender-id uuid?)

(s/def ::buffer? boolean?)

(s/def ::message
  (s/keys
   :opt-un [::buffer?]
   :req-un [::payload ::sender-id]))

(def buffer (rx/subject))

(defn- handle-message
  "Process the message and returns to the client"
  [{:keys [sender-id payload] :as message}]
  (us/assert ::message message)
  (letfn [(post [msg]
            (let [msg (-> msg (assoc :reply-to sender-id) (t/encode-str))]
              (.postMessage js/self msg)))

          (reply [result]
            (post {:payload result}))

          (reply-error [err]
            (.error js/console "error" err)
            (post {:error {:data (ex-data err)
                           :message (ex-message err)}}))

          (reply-completed
            ([] (reply-completed nil))
            ([msg] (post {:payload msg
                          :completed true})))]

    (try
      (let [result (impl/handler payload)
            promise? (p/promise? result)
            stream? (or (rx/observable? result) (rx/subject? result))]

        (cond
          promise?
          (-> result
              (p/then reply-completed)
              (p/catch reply-error))

          stream?
          (rx/subscribe result reply reply-error reply-completed)

          :else
          (reply result)))

      (catch :default err
        (reply-error err)))))

(defn- drop-message
  "Sends to the client a notification that its messages have been dropped"
  [{:keys [sender-id] :as message}]
  (us/assert ::message message)
  (.postMessage js/self (t/encode-str {:reply-to sender-id
                                       :dropped true})))

(defn subscribe-buffer-messages
  "Creates a subscription to process the buffer messages"
  []
  (let [empty [{} [] ::clear]]
    (->> buffer

         ;; We want async processing to not block the main loop
         (rx/observe-on :async)

         ;; This scan will store the last message per type in `messages`
         ;; when a previous message is dropped is stored in `dropped`
         ;; we also store the last message processed in order to detect
         ;; possible infinite loops
         (rx/scan
          (fn [[messages dropped _last] message]
            (let [cmd (get-in message [:payload :cmd])

                  ;; The previous message is dropped
                  dropped
                  (cond-> dropped
                    (contains? messages cmd)
                    (conj (get messages cmd)))

                  ;; This is the new "head" for its type
                  messages
                  (assoc messages cmd message)]

              ;; When a "clear" message is detected we empty the buffer
              (if (= message ::clear)
                empty
                [messages dropped message])))

          empty)

         ;; 1ms debounce, after 1ms without messages will process the buffer
         (rx/debounce 1)

         (rx/subs (fn [[messages dropped last]]
                    ;; Send back the dropped messages replies
                    (doseq [msg dropped]
                      (drop-message msg))

                    ;; Process the message
                    (doseq [msg (vals messages)]
                      (handle-message msg))

                    ;; After process the buffer we send a clear
                    (when-not (= last ::clear)
                      (rx/push! buffer ::clear)))))))

(defonce process-message-sub (subscribe-buffer-messages))

(defn- on-message
  [event]
  (when (nil? (.-source event))
    (let [message (.-data event)
          message (t/decode-str message)]
      (if (:buffer? message)
        (rx/push! buffer message)
        (handle-message message)))))

(.addEventListener js/self "message" on-message)

(defn ^:dev/before-load stop []
  (rx/-dispose process-message-sub)
  (.removeEventListener js/self "message" on-message))

(defn ^:dev/after-load start []
  []
  (set! process-message-sub (subscribe-buffer-messages))
  (.addEventListener js/self "message" on-message))

