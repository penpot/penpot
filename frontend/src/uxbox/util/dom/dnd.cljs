;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.util.dom.dnd
  "Drag & Drop interop helpers."
  (:require
    [uxbox.util.data :refer (read-string)]
    [uxbox.util.transit :as t]))

;; This is the official documentation for the dnd API:
;; https://developer.mozilla.org/en-US/docs/Web/API/HTML_Drag_and_Drop_API
;;
;; The API is broken in several ways. Here is some discussion of the problems,
;; and many uncomplete solutions:
;; https://github.com/lolmaus/jquery.dragbetter/#what-this-is-all-about
;; https://www.w3schools.com/jsref/event_relatedtarget.asp
;; https://stackoverflow.com/questions/14194324/firefox-firing-dragleave-when-dragging-over-text?noredirect=1&lq=1
;; https://stackoverflow.com/questions/7110353/html5-dragleave-fired-when-hovering-a-child-element

(defn trace
  ;; This function is useful to debug the erratic dnd interface behaviour when something weird occurs
  [event data label]
  (js/console.log
    label
    "[" (:name data) "]"
    (if (.-currentTarget event) (.-textContent (.-currentTarget event)) "null")
    (if (.-relatedTarget event) (.-textContent (.-relatedTarget event)) "null")))

(defn set-data!
  ([e data]
   (set-data! e "uxbox/data" data))
  ([e data-type data]
   (let [dt (.-dataTransfer e)]
     (.setData dt data-type (t/encode data))
     e)))

(defn set-drag-image!
  ([e image]
   (set-drag-image! e image 0 0))
  ([e image offset-x offset-y]
   (let [dt (.-dataTransfer e)]
     (.setDragImage dt image offset-x offset-y)
     e)))

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

(defn has-type?
  [e data-type]
  (let [dt (.-dataTransfer e)]
    (.includes (.-types dt) data-type)))

(defn from-child?
  [e]
  ;; The relatedTarget property contains the dom element that was under
  ;; the mouse *before* the event. This is useful, for example, to filter
  ;; out enter or over events initiated by children of the drop target.
  (let [target (.-currentTarget e)
        related (.-relatedTarget e)]
    (.contains target related)))

(defn get-data
  ([e]
   (get-data e "uxbox/data"))
  ([e data-type]
   (let [dt (.-dataTransfer e)]
     (t/decode (.getData dt data-type)))))

(defn get-files
  [e]
  (let [dt (.-dataTransfer e)]
    (.-files dt)))

(defn drop-side
  [e detect-center?]
  (let [ypos   (.-offsetY e)
        target (.-currentTarget e)
        height (.-clientHeight target)
        thold  (/ height 2)
        thold1 (* height 0.2)
        thold2 (* height 0.8)]
    (if detect-center?
      (cond
        (< ypos thold1) :top
        (> ypos thold2) :bot
        :else :center)
      (if (> ypos thold) :bot :top))))

