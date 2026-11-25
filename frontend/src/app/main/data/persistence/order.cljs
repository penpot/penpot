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
    (cond-> (assoc (update order :latest max revn)
                   :incremental-base (and (:incremental-base order) (:catch-up-verified message)))
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

(defn get-catch-up-rows
  "Verifies the response against revisions applied while it was in flight.
  Returns only rows still missing, or nil if the range contradicts the view."
  [{:keys [vern through seen] :as order} since until response]
  (when (map? response)
    (let [{end :through :keys [head rows]} response]
      (when (and (= vern (:vern response))
                 (not (:reload response))
                 (proven-base? order (:latest order))
                 (int? head) (int? end)
                 (<= since through end head)
                 (= end (or until head))
                 (vector? rows)
                 (<= (count rows) history-limit)
                 (= (- end since) (count rows))
                 (= (count rows) (count (into #{} (map :id) rows)))
                 (every? (fn [[n row]]
                           (let [revn (:revn row)
                                 id   (:id row)]
                             (and (= revn n) (uuid? id) (vector? (:changes row))
                                  (or (> revn through) (= id (get seen revn))))))
                         (map vector (range (inc since) (inc end)) rows)))
        (into [] (filter #(> (:revn %) through)) rows)))))

(defn- rejected-suffix?
  [commits {:keys [commit-ids failed-commit-id error]}]
  (and (vector? commit-ids)
       (<= 1 (count commit-ids) (count commits))
       (uuid? failed-commit-id)
       (= failed-commit-id (first commit-ids))
       (= commit-ids (mapv :commit-id (subvec commits (- (count commits) (count commit-ids)))))
       (keyword? (:type error))))

(defn- written-rows?
  [commits {:keys [revn written saved-revn rejected repair media-relinked edit-matches-request] :as answer}]
  (when (and (vector? written) (<= (count written) 2))
    (let [accepted (if rejected
                     (subvec commits 0 (- (count commits) (count (:commit-ids rejected))))
                     commits)
          saved?   (or (not rejected) (boolean (some (comp seq :changes) accepted)))
          kinds    (mapv :kind written)]
      (and (= saved? (boolean (seq written)))
           (every? uuid? (map :id written))
           (= (count written) (count (into #{} (map :id) written)))
           (= (map :revn written) (range (inc revn) (+ revn 1 (count written))))
           (or (not (contains? answer :media-relinked)) (boolean? media-relinked))
           (if saved?
             (= saved-revn (:revn (peek written)))
             (not (contains? answer :saved-revn)))
           (case kinds
             [:edit :repair]
             (and (map? repair)
                  (= (select-keys repair [:id :revn])
                     (select-keys (peek written) [:id :revn]))
                  (vector? (:changes repair))
                  (not media-relinked))

             [:repair :edit]
             (not (contains? answer :repair))

             ([] [:edit])
             (not (or (contains? answer :repair) media-relinked))

             false)
           (if (and (= [:edit] kinds) (not rejected))
             (boolean? edit-matches-request)
             (not (contains? answer :edit-matches-request)))))))

(defn valid-save-response?
  "Checks the POST confirmation against the exact request sent. Recovery
  evidence is separate: an unusable proof does not undo a confirmed write."
  [{sent :revn sent-vern :vern :keys [commits]} answer]
  (when (map? answer)
    (let [{:keys [revn vern rejected]} answer]
      (and (int? revn) (<= 0 sent revn)
           (int? vern) (= sent-vern vern)
           (or (not (contains? answer :rejected))
               (and (map? rejected) (rejected-suffix? commits rejected)))
           (written-rows? commits answer)))))

(defn- get-required-revisions
  [sent {:keys [revn written recovery]}]
  (when (map? recovery)
    (let [{:keys [mode since until required]} recovery]
      (cond
        (and (= sent revn) (= {:mode :none} recovery))
        {}

        (and (contains? #{:wait :catch-up :reload} mode)
             (= sent since) (= revn until) (< sent revn)
             (vector? required)
             (= (- revn sent) (count required))
             (<= (count required) history-limit)
             (= (map :revn required) (range (inc sent) (inc revn)))
             (every? uuid? (map :id required))
             (= (+ (count required) (count written))
                (count (into #{} (map :id) (concat required written)))))
        (into {} (map (juxt :revn :id)) required)))))

(defn rejected-base
  "The predecessor interval of a rejected request that wrote no row of
  its own, or nil when the server cannot prove the whole interval."
  [sent {:keys [revn] :as answer}]
  (when-let [required (get-required-revisions sent answer)]
    {:target revn :required required}))

(defn save-revisions
  "Proof needed to settle a confirmed edit. A repair written before the
  edit must also have applied by ID; one written after it applies inline."
  [sent {:keys [written] :as answer}]
  (when-let [required (get-required-revisions sent answer)]
    (let [[first-row second-row] written
          repair-first (= :repair (:kind first-row))
          edit         (if repair-first second-row first-row)]
      {:target (dec (:revn edit))
       :required (cond-> required repair-first (assoc (:revn first-row) (:id first-row)))
       :own (select-keys edit [:revn :id])
       :revision-ids (if repair-first [(:id edit)] (mapv :id written))})))

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
