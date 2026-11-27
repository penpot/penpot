;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.lookup
  "The shapes of a page that apply each token, found without decoding the
  shapes that are still encoded, and kept while the page doesn't change."
  (:require
   [app.common.types.objects-map :as omap]
   [app.common.uuid :as uuid]))

(def ^:private encoded-applied-tokens
  "Start of the applied tokens of a shape in its encoded form, a transit map
  of attribute to token name."
  "\"~:applied-tokens\",[\"^ \"")

(defn- encoded-string-end
  "Index after the JSON string that starts at `start` of `s`, or nil when
  there is no string there."
  [s start]
  (when (= "\"" (.charAt s start))
    (loop [i (inc start)]
      (case (.charAt s i)
        ""   nil
        "\\" (recur (+ i 2))
        "\"" (inc i)
        (recur (inc i))))))

(defn encoded-token-names
  "Names of the tokens applied to a shape, read from `encoded`, its encoded
  form, without decoding it. Nil when `encoded` has not the expected form."
  [encoded]
  (if (neg? (.indexOf encoded "applied-tokens"))
    []
    (let [start (.indexOf encoded encoded-applied-tokens)]
      (when-not (neg? start)
        (loop [pos   (+ start (count encoded-applied-tokens))
               name? false
               names []]
          (case (.charAt encoded pos)
            "]" (when-not name? names)
            "," (when-let [end (encoded-string-end encoded (inc pos))]
                  (let [value (subs encoded (inc pos) end)]
                    (cond
                      (not name?)
                      (recur end true names)

                      ;; Transit escapes strings that start with `~` or `^`
                      (re-find #"^\"[~^]" value)
                      nil

                      :else
                      (recur end false (conj names (js/JSON.parse value))))))
            nil))))))

;; Start of the parent id of a shape in its encoded form
(def ^:private encoded-parent-id "\"~:parent-id\",\"~u")

(defn- read-parent-id
  "Id of the parent of a shape, read from `encoded`, its encoded form,
  without decoding it. Nil when `encoded` has not the expected form."
  [encoded]
  (let [start (.indexOf encoded encoded-parent-id)]
    (when-not (neg? start)
      (let [from (+ start (count encoded-parent-id))]
        (uuid/parse* (subs encoded from (+ from 36)))))))

(defn parent-id
  "Id of the parent of the shape `id` of `objects`, read without decoding
  the shape when it is still encoded."
  [objects id]
  (or (when (omap/objects-map? objects)
        (some-> (omap/get-encoded objects id) read-parent-id))
      (:parent-id (get objects id))))

;; Index ----------------------------------------------------------------------

;; Index of each objects map: `{:by-token {name #{id}} :by-shape {id [name]}}`
(defonce ^:private cache (js/WeakMap.))

(defn- shape-token-names
  [objects id]
  (or (when (omap/objects-map? objects)
        (some-> (omap/get-encoded objects id) encoded-token-names))
      (some-> (get objects id) :applied-tokens vals)))

(defn- add-shape
  [index id token-names]
  (if (seq token-names)
    (-> index
        (assoc-in [:by-shape id] token-names)
        (update :by-token
                (fn [by-token]
                  (reduce #(update %1 %2 (fnil conj #{}) id) by-token token-names))))
    index))

(defn- remove-shape
  [index id]
  (let [token-names (get-in index [:by-shape id])]
    (-> index
        (update :by-shape dissoc id)
        (update :by-token
                (fn [by-token]
                  (reduce #(update %1 %2 disj id) by-token token-names))))))

(defn- build-index
  [objects]
  (reduce (fn [index id]
            (add-shape index id (shape-token-names objects id)))
          {:by-token {} :by-shape {}}
          (keys objects)))

(defn- get-index
  [objects]
  (or (.get cache objects)
      (let [index (build-index objects)]
        (.set cache objects index)
        index)))

(defn token-shape-ids
  "Map of token name to the set of ids of the shapes of `objects` that
  apply it. Built once for each version of `objects`, or carried over from
  the previous one with `advance`."
  [objects]
  (:by-token (get-index objects)))

(defn shapes-using
  "Ids of the shapes of `objects` that apply some of `token-names`."
  [objects token-names]
  (let [index (token-shape-ids objects)]
    (into #{} (mapcat #(get index %)) token-names)))

(defn- changed-ids
  "Ids of the shapes of `page-id` whose applied tokens `changes` may
  change, or nil when they change none."
  [page-id changes]
  (not-empty
   (into #{}
         (comp (filter #(= page-id (:page-id %)))
               (mapcat (fn [{:keys [type id operations]}]
                         (case type
                           (:add-obj :del-obj) [id]
                           :mod-obj (when (some #(or (= :assign (:type %))
                                                     (= :applied-tokens (:attr %)))
                                                operations)
                                      [id])
                           nil))))
         changes)))

(defn advance
  "Carry the index of `old-objects`, if there is one, over to `new-objects`,
  the objects of `page-id` after `changes`. Only the shapes whose applied
  tokens may change are read again."
  [page-id old-objects new-objects changes]
  (when (and (not (identical? old-objects new-objects))
             (not (.has cache new-objects)))
    (when-let [index (.get cache old-objects)]
      (.set cache new-objects
            (reduce (fn [index id]
                      (-> index
                          (remove-shape id)
                          (add-shape id (when (contains? new-objects id)
                                          (shape-token-names new-objects id)))))
                    index
                    (changed-ids page-id changes))))))
