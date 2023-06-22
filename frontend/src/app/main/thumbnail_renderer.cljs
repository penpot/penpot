;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.thumbnail-renderer
  "A main entry point for the thumbnail renderer API interface.

  This ns is responsible to provide an API for create thumbnail
  renderer iframes and interact with them using asyncrhonous
  messages."
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.util.dom :as dom]
   [beicon.core :as rx]
   [cuerdas.core :as str]))

(defonce ready? false)
(defonce queue #js [])
(defonce instance nil)
(defonce msgbus (rx/subject))
(defonce origin
  (dm/str (assoc cf/thumbnail-renderer-uri :path "/thumbnail-renderer.html")))

(declare send-message!)

(defn- process-queued-messages!
  []
  (loop [message (.shift ^js queue)]
    (when (some? message)
      (send-message! message)
      (recur (.shift ^js queue)))))

(defn- on-message
  "Handles a message from the thumbnail renderer."
  [event]
  (let [evorigin (unchecked-get event "origin")
        evdata   (unchecked-get event "data")]

    (when (and (object? evdata) (str/starts-with? origin evorigin))
      (let [scope (unchecked-get evdata "scope")
            type  (unchecked-get evdata "type")]
        (when (= "penpot/thumbnail-renderer" scope)
          (when (= type "ready")
            (set! ready? true)
            (process-queued-messages!))
          (rx/push! msgbus evdata))))))

(defn- send-message!
  "Sends a message to the thumbnail renderer."
  [message]
  (let [window (.-contentWindow ^js instance)]
    (.postMessage ^js window message origin)))

(defn- queue-message!
  "Queues a message to be sent to the thumbnail renderer when it's ready."
  [message]
  (.push ^js queue message))

(defn render
  "Renders a thumbnail."
  [{:keys [data styles] :as params}]
  (let [id      (dm/str (uuid/next))
        payload #js {:data data :styles styles}
        message #js {:id id
                     :scope "penpot/thumbnail-renderer"
                     :payload payload}]

    (if ^boolean ready?
      (send-message! message)
      (queue-message! message))

    (->> msgbus
         (rx/filter #(= id (unchecked-get % "id")))
         (rx/mapcat (fn [msg]
                      (case (unchecked-get msg "type")
                        "success" (rx/of (unchecked-get msg "payload"))
                        "failure" (rx/throw (unchecked-get msg "payload")))))
         (rx/take 1))))

(defn init!
  "Initializes the thumbnail renderer."
  []
  (let [iframe (dom/create-element "iframe")]
    (dom/set-attribute! iframe "src" origin)
    (dom/set-attribute! iframe "hidden" true)
    (dom/append-child! js/document.body iframe)

    (set! instance iframe)
    (.addEventListener js/window "message" on-message)))
