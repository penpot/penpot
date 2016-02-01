(ns uxbox.util.dom.dnd
  "Drag & Drop interop helpers."
  (:require [uxbox.util.data :refer (read-string)]))

(defn event->data-transfer
  [e]
  (.-dataTransfer e))

(defn set-allowed-effect!
  [e effect]
  (let [dt (.-dataTransfer e)]
    (set! (.-effectAllowed dt) effect)
    e))

(defn set-drop-effect!
  [e effect]
  (let [dt (.-dataTransfer e)]
    (set! (.-dropEffect dt) effect)
    e))

(defn set-data!
  ([e data]
   (set-data! e "uxbox/data" data))
  ([e key data]
   (let [dt (.-dataTransfer e)]
     (.setData dt (str key) (pr-str data)))))

(defn get-data
  ([e]
   (get-data e "uxbox/data"))
  ([e key]
   (let [dt (.-dataTransfer e)]
     (read-string (.getData dt (str key))))))
