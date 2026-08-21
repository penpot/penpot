;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.overlay.sync
  "Incremental overlay updates from Penpot change vectors.

  The invariant this namespace defends: an overlay maintained from change
  vectors equals an overlay rebuilt from the changed document. The change
  semantics are `app.common.files.changes/process-change`'s, mirrored
  clause by clause and cited per function; where a change touches state
  the overlay does not index (geometry, style payloads), the mirror is a
  no-op by design.

  Everything here is pure: db value in, db value out. The session layer
  owns the atom and the revision bookkeeping."
  (:require
   [app.common.types.component :as ctk]
   [app.common.types.page :as ctp]
   [app.common.uuid :as uuid]
   [app.graph.overlay :as overlay]
   [app.graph.overlay.queries :as queries]
   [datascript.core :as d]))

(def supported-change-types
  #{:add-obj :mod-obj :del-obj :mov-objects
    :add-page :del-page :mod-page
    :add-component :mod-component :del-component
    :restore-component :purge-component})

(defn supported-change?
  [change]
  (contains? supported-change-types (:type change)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resolution against the live db
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- doc-eid
  [db]
  (some-> (first (d/datoms db :avet :document/id)) :e))

(defn- sync-asset-ctx
  "The `asset-ctx` of `app.graph.overlay`, resolved against the live db:
  values are eids rather than tempids."
  [db]
  {:colors         (into {} (map (fn [dtm] [(:v dtm) (:e dtm)]))
                         (d/datoms db :avet :color/id))
   :typographies   (into {} (map (fn [dtm] [(:v dtm) (:e dtm)]))
                         (d/datoms db :avet :typography/id))
   :tokens-by-name (reduce (fn [acc dtm]
                             (update acc (:v dtm) (fnil conj []) (:e dtm)))
                           {}
                           (d/datoms db :avet :token/name))})

(defn- change-container-id
  [{:keys [page-id component-id]}]
  (or page-id component-id))

(defn- token-use-eids
  [db shape-eid]
  (mapv :e (d/datoms db :avet :token-use/shape shape-eid)))

(defn- retract-shape-tx
  "Retract one shape entity plus its applied-token relation entities
  (`:db.fn/retractEntity` clears datoms pointing at the shape, but the
  relation entity itself would survive as garbage)."
  [db eid]
  (into [[:db.fn/retractEntity eid]]
        (map (fn [tu] [:db.fn/retractEntity tu]))
        (token-use-eids db eid)))

(defn- child-eids
  [db eid]
  (mapv :e (d/datoms db :avet :shape/parent eid)))

(defn- subtree-eids
  "The shape entity plus every descendant, via the reverse parent index."
  [db root-eid]
  (loop [acc      []
         frontier [root-eid]]
    (if-let [eid (peek frontier)]
      (recur (conj acc eid)
             (into (pop frontier) (child-eids db eid)))
      acc)))

(defn- container-shape-eids
  [db container-eid]
  (mapv :e (d/datoms db :avet :shape/container container-eid)))

(defn- retract-container-shapes-tx
  [db container-eid]
  (into []
        (mapcat #(retract-shape-tx db %))
        (container-shape-eids db container-eid)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; per-change application
;;
;; Each function returns {:db db :applied? bool :reason kw?}.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ok
  [db]
  {:db db :applied? true})

(defn- skip
  [db reason]
  {:db db :applied? false :reason reason})

(defn- apply-add-obj
  "Mirror of `process-change :add-obj` -> `ctst/add-shape`: the effective
  parent is `(or parent-id frame-id)`, falling back to the root frame
  when absent from the container, then to the container itself."
  [db {:keys [id obj frame-id parent-id] :as change}]
  (let [container-id (change-container-id change)
        ceid         (queries/container-eid db container-id)]
    (if-not ceid
      (skip db :missing-container)
      (let [pid  (or parent-id frame-id)
            peid (or (when (and pid (not= pid id))
                       (queries/shape-eid db ceid pid))
                     (queries/shape-eid db ceid uuid/zero)
                     ceid)
            ctx      (sync-asset-ctx db)
            shape    (assoc obj :id id)
            tempid   (overlay/shape-tempid container-id id)
            existing (queries/shape-eid db ceid id)
            tx       (-> (if existing (retract-shape-tx db existing) [])
                         (conj (merge {:db/id           tempid
                                       :shape/id        id
                                       :shape/container ceid
                                       :shape/parent    peid}
                                      (overlay/shape-attrs shape)
                                      (overlay/shape-asset-attrs shape ctx)))
                         (into (overlay/token-use-entities
                                tempid (:applied-tokens shape)
                                (:tokens-by-name ctx))))]
        (ok (d/db-with db tx))))))

(def ^:private plain-attr->overlay
  {:name           :shape/name
   :component-id   :shape/component-id
   :component-file :shape/component-file
   :shape-ref      :shape/shape-ref})

(defn- replace-attr-tx
  [eid attr val]
  (if (some? val)
    [[:db/add eid attr val]]
    [[:db.fn/retractAttribute eid attr]]))

(defn- replace-many-tx
  [eid attr refs]
  (into [[:db.fn/retractAttribute eid attr]]
        (map (fn [r] [:db/add eid attr r]))
        refs))

(defn- swap-slot-tx
  "Mirror of `process-operation :set-touched`: `touched` only sticks on a
  shape inside a component copy, and the overlay only indexes the swap
  slot `ctk/get-swap-slot` extracts from it."
  [db eid touched]
  (let [in-copy? (some? (:shape/shape-ref (d/entity db eid)))
        slot     (when (and in-copy? (seq touched))
                   (ctk/get-swap-slot {:touched touched}))]
    (replace-attr-tx eid :shape/swap-slot slot)))

(defn- set-op-tx
  "Transaction data for one `:set` / `:set-touched` operation on `eid`.
  Attributes the overlay does not index yield nil (a designed no-op),
  mirroring `ctn/set-shape-attr` only for the indexed slice."
  [db eid ctx {:keys [type attr val touched]}]
  (cond
    (= :set-touched type)
    (swap-slot-tx db eid touched)

    (not= :set type)
    nil

    (contains? plain-attr->overlay attr)
    (replace-attr-tx eid (plain-attr->overlay attr) val)

    (= :touched attr)
    (swap-slot-tx db eid val)

    (= :fills attr)
    (replace-many-tx eid :shape/fill-color
                     (keep (:colors ctx) (overlay/fill-color-ref-ids val)))

    (= :strokes attr)
    (replace-many-tx eid :shape/stroke-color
                     (keep (:colors ctx) (overlay/stroke-color-ref-ids val)))

    (= :content attr)
    (concat (replace-many-tx eid :shape/text-color
                             (keep (:colors ctx) (overlay/content-color-ref-ids val)))
            (replace-many-tx eid :shape/uses-typography
                             (keep (:typographies ctx)
                                   (overlay/content-typography-ref-ids val))))

    (= :applied-tokens attr)
    (into (mapv (fn [tu] [:db.fn/retractEntity tu]) (token-use-eids db eid))
          (overlay/token-use-entities eid val (:tokens-by-name ctx)))

    :else nil))

(defn- apply-mod-obj
  [db {:keys [id operations] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        eid  (when ceid (queries/shape-eid db ceid id))]
    (if-not eid
      (skip db :missing-shape)
      (let [ctx (sync-asset-ctx db)
            tx  (into [] (mapcat #(set-op-tx db eid ctx %)) operations)]
        (ok (cond-> db (seq tx) (d/db-with tx)))))))

(defn- apply-del-obj
  [db {:keys [id] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        eid  (when ceid (queries/shape-eid db ceid id))]
    (if-not eid
      ;; Penpot emits one :del-obj per selected shape, so a parent's
      ;; deletion may already have removed this node; not a defect.
      (ok db)
      (ok (d/db-with db (into []
                              (mapcat #(retract-shape-tx db %))
                              (subtree-eids db eid)))))))

(defn- apply-mov-objects
  "Reparenting only: `:mov-objects` carries shape ids and a parent id and
  nothing else (`common/src/app/common/files/changes.cljc`), and the
  overlay stores no sibling order, so the whole change is one
  `:shape/parent` re-point per moved shape."
  [db {:keys [parent-id shapes] :as change}]
  (let [ceid (queries/container-eid db (change-container-id change))
        peid (when ceid (queries/shape-eid db ceid parent-id))]
    (if-not peid
      (skip db :missing-parent)
      (let [tx (into []
                     (keep (fn [sid]
                             (when-let [eid (queries/shape-eid db ceid sid)]
                               [:db/add eid :shape/parent peid])))
                     shapes)]
        (ok (cond-> db (seq tx) (d/db-with tx)))))))

(defn- apply-add-page
  "Mirror of `process-change :add-page`: id+name means a fresh empty page
  (`ctp/make-empty-page`), otherwise the provided page map."
  [db {:keys [id name page]}]
  (let [page (if (and (string? name) (uuid? id))
               (ctp/make-empty-page {:id id :name name})
               page)
        page-id (:id page)]
    (if (queries/container-eid db page-id)
      (ok db)
      (let [ctx (sync-asset-ctx db)
            tid (overlay/container-tempid page-id)
            tx  (into [(cond-> {:db/id              tid
                                :container/id       page-id
                                :container/kind     :page
                                :container/document (doc-eid db)}
                         (some? (:name page))
                         (assoc :container/name (:name page)))]
                      (overlay/container-entities tid page-id (:objects page) ctx))]
        (ok (d/db-with db tx))))))

(defn- apply-del-page
  [db {:keys [id]}]
  (if-let [ceid (queries/container-eid db id)]
    (ok (d/db-with db (conj (retract-container-shapes-tx db ceid)
                            [:db.fn/retractEntity ceid])))
    (skip db :missing-page)))

(defn- apply-mod-page
  [db {:keys [id name]}]
  (let [ceid (queries/container-eid db id)]
    (cond
      (nil? ceid)          (skip db :missing-page)
      (not (string? name)) (ok db)
      :else                (ok (d/db-with db [[:db/add ceid :container/name name]])))))

(defn- component-attrs-tx
  [eid {:keys [name main-instance-id main-instance-page]}]
  (cond-> []
    (some? name)               (conj [:db/add eid :component/name name])
    (some? main-instance-id)   (conj [:db/add eid :component/main-instance-id main-instance-id])
    (some? main-instance-page) (conj [:db/add eid :component/main-instance-page main-instance-page])))

(defn- apply-add-component
  "Mirror of `ctkl/add-component`; objects never ride an add."
  [db {:keys [id] :as change}]
  (if (d/entid db [:component/id id])
    (ok db)
    (let [tx (into [{:db/id -1 :component/id id :component/document (doc-eid db)}]
              (component-attrs-tx -1 change))]
      (ok (d/db-with db tx)))))

(defn- clear-component-container-tx
  "Drop a component's own shape copies and its container role, keeping the
  component record (mirror of the `dissoc :objects` in
  `ctkl/mod-component` and `ctf/restore-component`)."
  [db eid]
  (into (retract-container-shapes-tx db eid)
        [[:db.fn/retractAttribute eid :container/id]
         [:db.fn/retractAttribute eid :container/kind]
         [:db.fn/retractAttribute eid :container/document]]))

(defn- component-container-tx
  "Give the component entity a container role over `objects`."
  [db eid component-id objects]
  (let [ctx (sync-asset-ctx db)]
    (into [[:db/add eid :container/id component-id]
           [:db/add eid :container/kind :component]
           [:db/add eid :container/document (doc-eid db)]]
          (overlay/container-entities eid component-id objects ctx))))

(defn- apply-mod-component
  "Mirror of `ctkl/mod-component`, including its sharpest edge: a
  `:mod-component` without `:objects` dissocs the component's own copy,
  so the overlay drops the component container either way and rebuilds it
  only when the change carries objects."
  [db {:keys [id objects] :as change}]
  (if-let [eid (d/entid db [:component/id id])]
    (let [tx (-> (component-attrs-tx eid change)
                 (into (clear-component-container-tx db eid))
                 (cond-> (some? objects)
                   (into (component-container-tx db eid id objects))))]
      (ok (cond-> db (seq tx) (d/db-with tx))))
    (skip db :missing-component)))

(defn- entity-shape-attrs
  "The indexed attrs of a live shape entity, as assertable values (asset
  references flattened to eids)."
  [ent]
  (let [many-ids #(into [] (map :db/id) %)]
    (cond-> {:shape/type (:shape/type ent)}
      (some? (:shape/name ent))           (assoc :shape/name (:shape/name ent))
      (some? (:shape/component-id ent))   (assoc :shape/component-id (:shape/component-id ent))
      (some? (:shape/component-file ent)) (assoc :shape/component-file (:shape/component-file ent))
      (some? (:shape/shape-ref ent))      (assoc :shape/shape-ref (:shape/shape-ref ent))
      (some? (:shape/swap-slot ent))      (assoc :shape/swap-slot (:shape/swap-slot ent))
      (seq (:shape/fill-color ent))       (assoc :shape/fill-color (many-ids (:shape/fill-color ent)))
      (seq (:shape/stroke-color ent))     (assoc :shape/stroke-color (many-ids (:shape/stroke-color ent)))
      (seq (:shape/text-color ent))       (assoc :shape/text-color (many-ids (:shape/text-color ent)))
      (seq (:shape/uses-typography ent))  (assoc :shape/uses-typography (many-ids (:shape/uses-typography ent))))))

(defn- snapshot-component-tx
  "Mirror of `ctf/load-component-objects`: copy the main-instance subtree
  from its page into the component's own container, same ids, new
  (container, shape) entities. The copy is taken from the overlay itself,
  which is exactly the identity/type/topology slice the rebuild would
  project from the document's copy. Geometry (`delta`) is not indexed, so
  the move that accompanies an undo of cut-paste is a designed no-op."
  [db comp-eid]
  (let [ent     (d/entity db comp-eid)
        comp-id (:component/id ent)
        mid     (:component/main-instance-id ent)
        mpg     (:component/main-instance-page ent)
        pceid   (when mpg (queries/container-eid db mpg))
        root    (when (and pceid mid) (queries/shape-eid db pceid mid))]
    (when (and root (empty? (container-shape-eids db comp-eid)))
      (let [eids   (subtree-eids db root)
            eidset (set eids)
            copy-tempid (fn [eid]
                          (overlay/shape-tempid comp-id (:shape/id (d/entity db eid))))]
        (into [[:db/add comp-eid :container/id comp-id]
               [:db/add comp-eid :container/kind :component]
               [:db/add comp-eid :container/document (doc-eid db)]]
              (mapcat (fn [eid]
                        (let [src    (d/entity db eid)
                              tempid (copy-tempid eid)
                              parent (:db/id (:shape/parent src))
                              copy   (merge {:db/id           tempid
                                             :shape/id        (:shape/id src)
                                             :shape/container comp-eid
                                             :shape/parent    (if (contains? eidset parent)
                                                                (copy-tempid parent)
                                                                comp-eid)}
                                            (entity-shape-attrs src))
                              uses   (for [tu (token-use-eids db eid)]
                                       (let [tue (d/entity db tu)]
                                         {:token-use/shape tempid
                                          :token-use/token (:db/id (:token-use/token tue))
                                          :token-use/prop  (:token-use/prop tue)}))]
                          (cons copy uses))))
              eids)))))

(defn- apply-del-component
  "Mirror of `ctf/delete-component`: with `skip-undelete?` the component
  goes entirely; otherwise it is marked deleted and keeps its own copy of
  the main-instance subtree."
  [db {:keys [id skip-undelete?]}]
  (if-let [eid (d/entid db [:component/id id])]
    (if skip-undelete?
      (ok (d/db-with db (conj (retract-container-shapes-tx db eid)
                              [:db.fn/retractEntity eid])))
      (ok (d/db-with db (into [[:db/add eid :component/deleted true]]
                              (snapshot-component-tx db eid)))))
    (ok db)))

(defn- apply-restore-component
  "Mirror of `ctf/restore-component`: undelete, drop the component's own
  copy, optionally repoint the main-instance page."
  [db {:keys [id page-id]}]
  (if-let [eid (d/entid db [:component/id id])]
    (let [tx (cond-> (into [[:db.fn/retractAttribute eid :component/deleted]]
                           (clear-component-container-tx db eid))
               (some? page-id)
               (conj [:db/add eid :component/main-instance-page page-id]))]
      (ok (d/db-with db tx)))
    (skip db :missing-component)))

(defn- apply-purge-component
  [db {:keys [id]}]
  (if-let [eid (d/entid db [:component/id id])]
    (ok (d/db-with db (conj (retract-container-shapes-tx db eid)
                            [:db.fn/retractEntity eid])))
    (ok db)))

(defn- apply-change
  [db change]
  (case (:type change)
    :add-obj           (apply-add-obj db change)
    :mod-obj           (apply-mod-obj db change)
    :del-obj           (apply-del-obj db change)
    :mov-objects       (apply-mov-objects db change)
    :add-page          (apply-add-page db change)
    :del-page          (apply-del-page db change)
    :mod-page          (apply-mod-page db change)
    :add-component     (apply-add-component db change)
    :mod-component     (apply-mod-component db change)
    :del-component     (apply-del-component db change)
    :restore-component (apply-restore-component db change)
    :purge-component   (apply-purge-component db change)
    (skip db :unsupported-type)))

(defn apply-changes
  "Apply Penpot `changes` to overlay `db`. Pure.

  Returns `{:db db' :applied [types...] :skipped [{:type .. :reason ..}]}`."
  [db changes]
  (reduce (fn [acc change]
            (let [{:keys [db applied? reason]} (apply-change (:db acc) change)]
              (-> acc
                  (assoc :db db)
                  (cond->
                   applied?       (update :applied conj (:type change))
                   (not applied?) (update :skipped conj {:type   (:type change)
                                                         :reason reason})))))
          {:db db :applied [] :skipped []}
          changes))
