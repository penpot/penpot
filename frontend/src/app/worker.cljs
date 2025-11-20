;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.schema :as sm]
   [app.common.types.objects-map]
   [app.util.object :as obj]
   [app.worker.impl :as impl]
   [app.worker.import]
   [app.worker.index]
   [app.worker.messages :as wm]
   [app.worker.thumbnails]
   [beicon.v2.core :as rx]
   [promesa.core :as p]))

(log/setup! {:app :info})

;; --- Messages Handling

(def ^:private schema:message
  [:map {:title "WorkerMessage"}
   [:sender-id ::sm/uuid]
   [:payload
    [:map
     [:cmd :keyword]]]
   [:buffer? {:optional true} :boolean]])

(def ^:private check-message
  (sm/check-fn schema:message))

(def buffer (rx/subject))

(defn- handle-message
  "Process the message and returns to the client"
  [{:keys [sender-id payload transfer] :as message}]

  (assert (check-message message))

  (letfn [(post [msg]
            (let [msg (-> msg (assoc :reply-to sender-id) (wm/encode))]
              (.postMessage js/self msg)))

          (reply [result]
            (post {:payload result}))

          (reply-error [cause]
            (if (map? cause)
              (post {:error {:type :worker-error
                             :code (or (:type cause) :wrapped)
                             :data cause}})
              (post {:error {:type :worker-error
                             :code :unhandled-error
                             :hint (ex-message cause)
                             :data (ex-data cause)}})))

          (reply-completed
            ([] (reply-completed nil))
            ([msg]
             (post {:payload msg
                    :completed true})))]

    (try
      (let [result (impl/handler payload transfer)
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
  (dm/assert!
   "expected valid message"
   (sm/check schema:message message))
  (.postMessage js/self (wm/encode {:reply-to sender-id
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

         (rx/subs! (fn [[messages dropped last]]
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
          transfer (obj/get message "transfer")
          message (cond-> (wm/decode message)
                    (some? transfer)
                    (assoc :transfer transfer))]
      (if (:buffer? message)
        (rx/push! buffer message)
        (handle-message message)))))

(.addEventListener js/self "message" on-message)

(defn ^:dev/before-load stop []
  (rx/-dispose process-message-sub)
  (.removeEventListener js/self "message" on-message))

(defn ^:dev/after-load start []
  (set! process-message-sub (subscribe-buffer-messages))
  (.addEventListener js/self "message" on-message))

