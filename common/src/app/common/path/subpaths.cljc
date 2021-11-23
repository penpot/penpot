;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.path.subpaths
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.path.commands :as upc]))

(defn pt=
  "Check if two points are close"
  [p1 p2]
  (< (gpt/distance p1 p2) 0.1))

(defn make-subpath
  "Creates a subpath either from a single command or with all the data"
  ([command]
   (let [p (upc/command->point command)]
     (make-subpath p p [command])))
  ([from to data]
   {:from from
    :to to
    :data data}))

(defn add-subpath-command
  "Adds a command to the subpath"
  [subpath command]
  (let [command (if (= :close-path (:command command))
                  (upc/make-line-to (:from subpath))
                  command)
        p (upc/command->point command)]
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
                      (reduce reverse-commands [(upc/make-move-to (:to subpath))]))]

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
      (update :data d/concat (rest (:data other)))
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

(defn close-subpaths
  "Searches a path for possible supaths that can create closed loops and merge them"
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

    (->> closed-subpaths
         (mapcat :data)
         (into []))))

(defn reverse-content
  "Given a content reverse the order of the commands"
  [content]

  (->> content
       (get-subpaths)
       (mapv reverse-subpath)
       (reverse)
       (mapcat :data)
       (into [])))

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

        (let [{x1 :x y1 :y :as p} (upc/command->point current)
              last? (nil? (first subpath))
              first-point (if (nil? first-point) p first-point)
              {x2 :x y2 :y} (if last? first-point (upc/command->point (first subpath)))
              signed-area (+ signed-area (- (* x1 y2) (* x2 y1)))]

          (recur (first subpath)
                 (rest subpath)
                 first-point
                 signed-area))))))
