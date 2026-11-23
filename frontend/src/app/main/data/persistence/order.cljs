;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.persistence.order
  "Evidence for ordering deltas against the base of a loaded file.
  Revision numbers locate a change; only row IDs identify it.")

(def ^:private history-limit 128)

(defn loaded
  [vern revn]
  {:vern vern :covered revn :through revn :latest revn :seen {}
   :opened-revn revn :announced-repairs #{}})

(defn recovered
  "Sets a new base for deltas without forgetting this opening's repair notices."
  [order revn]
  (merge (loaded (:vern order) revn)
         (select-keys order [:opened-revn :announced-repairs :repair-notice-floor])))

(defn recovered-repair?
  [{:keys [covered opened-revn]} [revn _]]
  (and (< opened-revn revn)
       (<= revn covered)))

(defn announced-repair?
  [{:keys [announced-repairs repair-notice-floor]} [revn id]]
  (or (and repair-notice-floor (<= revn repair-notice-floor))
      (if id
        (or (contains? announced-repairs [revn id])
            (contains? announced-repairs [revn nil]))
        (some #(= revn (first %)) announced-repairs))))

(defn announce-repairs
  [{:keys [announced-repairs] :as order} repairs]
  (let [announced (into announced-repairs repairs)
        excess    (- (count announced) history-limit)]
    (if (pos? excess)
      ;; Retire the evicted revisions so delayed duplicates cannot show again.
      (let [floor (first (nth (sort-by first announced) (dec excess)))]
        (assoc order :repair-notice-floor floor
               :announced-repairs (into #{} (filter #(> (first %) floor)) announced)))
      (assoc order :announced-repairs announced))))

(defn- message-base
  [{:keys [revn repair-base-revn]}]
  (or repair-base-revn (dec revn)))

(defn- revision-rows
  [{:keys [revision-ids] :as message}]
  (map-indexed (fn [i id] [(+ (message-base message) 1 i) id]) revision-ids))

(defn- known?
  [{:keys [covered seen]} {:keys [revn] :as message}]
  (or (<= revn covered)
      (and (seq (:revision-ids message))
           (every? (fn [[revn id]] (= id (get seen revn))) (revision-rows message)))))

(defn proven-base?
  [{:keys [through gap]} revn]
  (and (not gap) (= through revn)))

(defn decision
  "Decides whether a new event can be installed now. A library stream is
  filtered, so revisions can skip numbers; they still cannot go backwards."
  [{:keys [latest] :as order}
   {:keys [revn repaired repair-base-revn revision-ids] :as message} library?]
  (cond
    (known? order message)
    :ignore

    (some? repair-base-revn)
    (if (if library?
          (<= latest repair-base-revn)
          (and (= latest repair-base-revn)
               (proven-base? order repair-base-revn)
               (= 2 (- revn repair-base-revn)
                  (count (distinct revision-ids)))))
      :apply
      :reconcile)

    repaired
    ;; A legacy file message can lack a row ID. It can still apply on an
    ;; intact predecessor, but will not extend the proof of any later base.
    (if (if library?
          (< latest revn)
          (and (= (inc latest) revn)
               (proven-base? order latest)
               (<= (count revision-ids) 1)))
      :apply
      :reconcile)

    (or (<= revn latest)
        (and (not library?) (> revn (inc latest))))
    :reconcile

    :else
    :apply))

(defn applied
  "Records a message only after applying its changes (or confirming the
  local edit at the corresponding row)."
  [{:keys [latest] :as order} {:keys [revn] :as message}]
  (let [base (message-base message)]
    (cond-> (update order :latest max revn)
      (and (= base latest)
           (proven-base? order base)
           (= (- revn base) (count (:revision-ids message))))
      (assoc :through revn)

      (or (not= base latest)
          (not= (- revn base) (count (:revision-ids message))))
      (assoc :gap true)

      (seq (:revision-ids message))
      (update :seen (fn [seen]
                      (into {} (filter #(> (key %) (- revn history-limit)))
                            (into seen (revision-rows message))))))))

(defn- distinct-interval?
  [sent target rows]
  (and (= (- target sent) (count rows))
       (= (count rows) (count (into #{} (map :id) (mapcat val rows))))
       (every? (fn [[n revisions]]
                 (and (<= (inc sent) n target)
                      (= 1 (count revisions))
                      (uuid? (:id (first revisions)))))
               rows)))

(defn rejected-base
  "The predecessor interval of a fully rejected request, which wrote no
  row of its own. Nil if the response does not establish that interval."
  [sent {:keys [revn lagged]}]
  (let [rows (group-by :revn (filter #(> (:revn %) sent) lagged))]
    (when (distinct-interval? sent revn rows)
      {:target revn
       :required (into {} (map (fn [[n [row]]] [n (:id row)])) rows)})))

(defn save-revisions
  "Extracts the required predecessors and the written rows by revision,
  not by the server's legacy created-at ordering. Returns nil if the
  response does not describe an unambiguous interval after `sent`."
  [sent session-id {:keys [revn saved-revn lagged repair]}]
  (let [edit-revn (if repair (dec saved-revn) saved-revn)
        target    (dec edit-revn)
        by-revn   (group-by :revn (filter #(> (:revn %) sent) lagged))
        row       #(first (get by-revn %))
        edit      (row edit-revn)
        repaired  (when repair (row saved-revn))]
    (when (and (<= sent revn target (inc revn))
               (if repair (= revn target) true)
               (distinct-interval? sent saved-revn by-revn)
               (= session-id (:session-id edit))
               (or (nil? repair)
                   (= repair (select-keys repaired [:id :revn :changes]))))
      {:target target
       :required (into {} (map (fn [[n [r]]] [n (:id r)]))
                       (filter #(<= (key %) target) by-revn))
       :revision-ids (cond-> [(:id edit)] repair (conj (:id repaired)))})))

(defn save-decision
  [{:keys [latest through seen gap]} {:keys [target required]}]
  (cond
    (or gap (> latest target)
        (some (fn [[n id]] (and (<= n through) (not= id (get seen n)))) required))
    :reconcile

    (= target through)
    :apply

    :else
    :wait))
