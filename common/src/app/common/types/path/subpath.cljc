;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.types.path.subpath
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.types.path.helpers :as helpers]))

(defn pt=
  "Check if two points are close"
  [p1 p2]
  (< (gpt/distance p1 p2) 0.1))

(defn make-subpath
  "Creates a subpath either from a single command or with all the data"
  ([command]
   (let [p (helpers/segment->point command)]
     (make-subpath p p [command])))
  ([from to data]
   {:from from
    :to to
    :data data}))

(defn add-subpath-command
  "Adds a command to the subpath"
  [subpath command]
  (let [command (if (= :close-path (:command command))
                  (helpers/make-line-to (:from subpath))
                  command)
        p (helpers/segment->point command)]
    (-> subpath
        (assoc :to p)
        (update :data conj command))))

(defn reverse-command
  "Reverses a single command"
  [command prev]

  (let [{:keys [x y]} (:params prev)
        {:keys [c1x c1y c2x c2y]} (:params command)]

    (-> command
        (update :params assoc :x x :y y)

        (cond-> (= :curve-to (:command command))
          (update :params assoc
                  :c1x c2x :c1y c2y
                  :c2x c1x :c2y c1y)))))

(defn reverse-subpath
  "Reverses a subpath starting with move-to"
  [subpath]

  (let [reverse-commands
        (fn [result [command prev]]
          (if (some? prev)
            (conj result (reverse-command command prev))
            result))

        new-data (->> subpath :data d/with-prev reverse
                      (reduce reverse-commands [(helpers/make-move-to (:to subpath))]))]

    (make-subpath (:to subpath) (:from subpath) new-data)))

(defn get-subpaths
  "Retrieves every subpath inside the current content"
  [content]
  (let [reduce-subpath
        (fn [subpaths current]
          (let [is-move? (= :move-to (:command current))
                last-idx (dec (count subpaths))]
            (cond
              is-move?
              (conj subpaths (make-subpath current))

              (>= last-idx 0)
              (update subpaths last-idx add-subpath-command current)

              :else
              subpaths)))]
    (->> content
         (reduce reduce-subpath []))))

(defn subpaths-join
  "Join two subpaths together when the first finish where the second starts"
  [subpath other]
  (assert (pt= (:to subpath) (:from other)))
  (-> subpath
      (update :data d/concat-vec (rest (:data other)))
      (assoc :to (:to other))))

(defn- merge-paths
  "Tries to merge into candidate the subpaths. Will return the candidate with the subpaths merged
  and removed from subpaths the subpaths merged"
  [candidate subpaths]
  (let [merge-with-candidate
        (fn [[candidate result] current]
          (cond
            (pt= (:to current) (:from current))
            ;; Subpath is already a closed path
            [candidate (conj result current)]

            (pt= (:to candidate) (:from current))
            [(subpaths-join candidate current) result]

            (pt= (:from candidate) (:to current))
            [(subpaths-join current candidate) result]

            (pt= (:to candidate) (:to current))
            [(subpaths-join candidate (reverse-subpath current)) result]

            (pt= (:from candidate) (:from current))
            [(subpaths-join (reverse-subpath current) candidate) result]

            :else
            [candidate (conj result current)]))]

    (->> subpaths
         (reduce merge-with-candidate [candidate []]))))

(defn is-closed? [subpath]
  (pt= (:from subpath) (:to subpath)))

(def ^:private xf-mapcat-data
  (mapcat :data))

(defn- join-adjacent
  "Fold neighbouring subpaths into the accumulator only when the
  current accumulator's end-point matches the next subpath's start-point.
  Unlike `merge-paths` this does not reverse subpaths nor reorder them;
  the original draw order is preserved so stroke-dasharray and animation
  semantics stay intact."
  [acc subpath]
  (if-let [prev (peek acc)]
    (if (and (not (is-closed? prev))
             (not (is-closed? subpath))
             (pt= (:to prev) (:from subpath)))
      (conj (pop acc) (subpaths-join prev subpath))
      (conj acc subpath))
    (conj acc subpath)))

(defn merge-touching-subpaths
  "Merge consecutive subpaths whose endpoints coincide into a single
  continuous subpath, preserving the original drawing order.

  This is a conservative variant of `close-subpaths`: it never reverses
  a subpath and only merges immediate neighbours, so closed regions and
  fill semantics are left untouched. The intent is to recover the
  `stroke-linejoin` rendering for SVG paths whose authoring tools split
  a continuous polyline into adjacent `M..L M..L` subpaths (e.g. the
  `m0 0` markers Figma emits when exporting Heroicons-like icons)."
  [content]
  (let [subpaths (get-subpaths content)
        merged   (reduce join-adjacent [] subpaths)]
    (into [] xf-mapcat-data merged)))

(defn close-subpaths
  "Searches a path for possible subpaths that can create closed loops and merge them"
  [content]
  (let [subpaths (get-subpaths content)
        closed-subpaths
        (loop [result []
               current (first subpaths)
               subpaths (rest subpaths)]

          (if (some? current)
            (let [[new-current new-subpaths]
                  (if (is-closed? current)
                    [current subpaths]
                    (merge-paths current subpaths))]

              (if (= current new-current)
                ;; If equal we haven't found any matching subpaths we advance
                (recur (conj result new-current)
                       (first new-subpaths)
                       (rest new-subpaths))

                ;; If different we need to pass again the merge to check for additional
                ;; subpaths to join
                (recur result
                       new-current
                       new-subpaths)))
            result))]


    (into [] xf-mapcat-data closed-subpaths)))

;; FIXME: revisit this fn impl for perfromance
(defn reverse-content
  "Given a content reverse the order of the commands"
  [content]
  (->> (get-subpaths content)
       (mapv reverse-subpath)
       (reverse)
       (into [] xf-mapcat-data)))

;; https://mathworld.wolfram.com/PolygonArea.html
(defn clockwise?
  "Check whether the first subpath is clockwise or counter-clock wise"
  [content]
  (let [subpath (->> content get-subpaths first :data)]
    (loop [current (first subpath)
           subpath (rest subpath)
           first-point nil
           signed-area 0]

      (if (nil? current)
        (> signed-area 0)

        (let [{x1 :x y1 :y :as p} (helpers/segment->point current)
              last? (nil? (first subpath))
              first-point (if (nil? first-point) p first-point)
              {x2 :x y2 :y} (if last? first-point (helpers/segment->point (first subpath)))
              signed-area (+ signed-area (- (* x1 y2) (* x2 y1)))]

          (recur (first subpath)
                 (rest subpath)
                 first-point
                 signed-area))))))
