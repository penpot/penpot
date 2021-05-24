;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.path.subpaths
  (:require
   [app.common.data :as d]
   [app.util.path.commands :as upc]))

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
            (if is-move?
              (conj subpaths (make-subpath current))
              (update subpaths last-idx add-subpath-command current))))]
    (->> content
         (reduce reduce-subpath []))))

(defn subpaths-join
  "Join two subpaths together when the first finish where the second starts"
  [subpath other]
  (assert (= (:to subpath) (:from other)))
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
            (= (:to current) (:from current))
            [candidate (conj result current)]

            (= (:to candidate) (:from current))
            [(subpaths-join candidate current) result]

            (= (:to candidate) (:to current))
            [(subpaths-join candidate (reverse-subpath current)) result]

            :else
            [candidate (conj result current)]))]

    (->> subpaths
         (reduce merge-with-candidate [candidate []]))))

(defn close-subpaths
  "Searches a path for posible supaths that can create closed loops and merge them"
  [content]
  (let [subpaths (get-subpaths content)
        closed-subpaths
        (loop [result []
               current (first subpaths)
               subpaths (rest subpaths)]

          (if (some? current)
            (let [[new-current new-subpaths]
                  (if (= (:from current) (:to current))
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
