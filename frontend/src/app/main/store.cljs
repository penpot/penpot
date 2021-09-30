;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.store
  (:require-macros [app.main.store])
  (:require
   [app.common.data :as d]
   [app.common.pages :as cp]
   [app.util.debug :refer [debug? debug-exclude-events logjs]]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]))

(enable-console-print!)

(defonce loader (l/atom false))
(defonce on-error (l/atom identity))

(defonce state
  (ptk/store {:resolve ptk/resolve
              :on-error (fn [e] (@on-error e))}))

(defonce stream
  (ptk/input-stream state))

(defonce last-events
  (let [buffer (atom #queue [])
        remove #{:potok.core/undefined
                 :app.main.data.workspace.notifications/handle-pointer-update}]
    (->> stream
         (rx/filter ptk/event?)
         (rx/map ptk/type)
         (rx/filter (complement remove))
         (rx/map str)
         (rx/dedupe)
         (rx/buffer 20 1)
         (rx/subs #(reset! buffer %)))

    buffer))

(when *assert*
  (defonce debug-subscription
    (->> stream
         (rx/filter ptk/event?)
         (rx/filter (fn [s] (and (debug? :events)
                                 (not (debug-exclude-events (ptk/type s))))))
         (rx/subs #(println "[stream]: " (ptk/repr-event %))))))

(defn emit!
  ([] nil)
  ([event]
   (ptk/emit! state event)
   nil)
  ([event & events]
   (apply ptk/emit! state (cons event events))
   nil))

(defn emitf
  [& events]
  #(apply ptk/emit! state events))

(defn ^:export dump-state []
  (logjs "state" @state))

(defn ^:export dump-buffer []
  (logjs "state" @last-events))

(defn ^:export get-state [str-path]
  (let [path (->> (str/split str-path " ")
                  (map d/read-string))]
    (clj->js (get-in @state path))))

(defn ^:export dump-objects []
  (let [page-id (get @state :current-page-id)]
    (logjs "state" (get-in @state [:workspace-data :pages-index page-id :objects]))))

(defn ^:export dump-object [name]
  (let [page-id (get @state :current-page-id)
        objects (get-in @state [:workspace-data :pages-index page-id :objects])
        target  (or (d/seek (fn [[_ shape]] (= name (:name shape))) objects)
                    (get objects (uuid name)))]
    (->> target
         (logjs "state"))))

(defn ^:export dump-tree
  ([] (dump-tree false false))
  ([show-ids] (dump-tree show-ids false))
  ([show-ids show-touched]
   (let [page-id    (get @state :current-page-id)
         objects    (get-in @state [:workspace-data :pages-index page-id :objects])
         components (get-in @state [:workspace-data :components])
         libraries  (get @state :workspace-libraries)
         root (d/seek #(nil? (:parent-id %)) (vals objects))]

     (letfn [(show-shape [shape-id level objects]
               (let [shape (get objects shape-id)]
                 (println (str/pad (str (str/repeat "  " level)
                                        (:name shape)
                                        (when (seq (:touched shape)) "*")
                                        (when show-ids (str/format " <%s>" (:id shape))))
                                   {:length 20
                                    :type :right})
                          (show-component shape objects))
                 (when show-touched
                   (when (seq (:touched shape))
                     (println (str (str/repeat "  " level)
                                 "    "
                                 (str (:touched shape)))))
                   (when (:remote-synced? shape)
                     (println (str (str/repeat "  " level)
                                 "    (remote-synced)"))))
                 (when (:shapes shape)
                   (dorun (for [shape-id (:shapes shape)]
                            (show-shape shape-id (inc level) objects))))))

             (show-component [shape objects]
               (if (nil? (:shape-ref shape))
                 ""
                 (let [root-shape        (cp/get-component-shape shape objects)
                       component-id      (when root-shape (:component-id root-shape))
                       component-file-id (when root-shape (:component-file root-shape))
                       component-file    (when component-file-id (get libraries component-file-id nil))
                       component         (when component-id
                                           (if component-file
                                             (get-in component-file [:data :components component-id])
                                             (get components component-id)))
                       component-shape   (when (and component (:shape-ref shape))
                                           (get-in component [:objects (:shape-ref shape)]))]
                   (str/format " %s--> %s%s%s"
                               (cond (:component-root? shape) "#"
                                     (:component-id shape) "@"
                                     :else "-")
                               (when component-file (str/format "<%s> " (:name component-file)))
                               (or (:name component-shape) "?")
                               (if (or (:component-root? shape)
                                       (nil? (:component-id shape))
                                       true)
                                 ""
                                 (let [component-id      (:component-id shape)
                                       component-file-id (:component-file shape)
                                       component-file    (when component-file-id (get libraries component-file-id nil))
                                       component         (if component-file
                                                           (get-in component-file [:data :components component-id])
                                                           (get components component-id))]
                                   (str/format " (%s%s)"
                                               (when component-file (str/format "<%s> " (:name component-file)))
                                               (:name component))))))))]

       (println "[Page]")
       (show-shape (:id root) 0 objects)

       (dorun (for [component (vals components)]
                (do
                  (println)
                  (println (str/format "[%s]" (:name component)))
                  (show-shape (:id component) 0 (:objects component)))))))))

