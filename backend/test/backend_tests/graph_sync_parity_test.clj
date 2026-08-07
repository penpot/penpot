;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.graph-sync-parity-test
  "Cold projection and incremental sync are two implementations of one mapping,
  and this namespace holds them to it.

  `app.graph.projection.document/projection-data` reads a whole file and produces
  the whole graph. `app.graph.sync/apply-changes!` takes the change vocabulary
  the editor emits and mutates an already open graph. A graph the second one
  maintained must equal a graph the first one would build from the same file,
  or the console shows a graph no rebuild reproduces.

  The round trip: project a file cold into A, apply a change list to A and the
  same list to the file data, project the resulting data cold into B, and diff
  A against B. Two `:memory:` databases, no Postgres, no session."
  (:require
   [app.common.features :as ffeat]
   [app.common.files.changes :as cfc]
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.graph.arrow :as arrow]
   [app.graph.ladybug :as ladybug]
   [app.graph.projection.document :as projection.document]
   [app.graph.projection.transforms :as projection.transforms]
   [app.graph.schema.nodes :as nodes]
   [app.graph.sync :as sync]
   [clojure.test :as t]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the fixture file
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Fixed ids: a failure should read the same on every run.
(def ^:private file-id  #uuid "00000000-0000-0000-0000-00000000f11e")
(def ^:private page-id  #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private page2-id #uuid "00000000-0000-0000-0000-0000000000a2")
(def ^:private frame-id #uuid "00000000-0000-0000-0000-0000000000f1")
(def ^:private rect-id  #uuid "00000000-0000-0000-0000-0000000000b1")
(def ^:private circ-id  #uuid "00000000-0000-0000-0000-0000000000b2")
(def ^:private text-id  #uuid "00000000-0000-0000-0000-0000000000b3")
(def ^:private rect2-id #uuid "00000000-0000-0000-0000-0000000000b4")

(def ^:private base-revn 1)

(defn- file-row
  "The `file` map the projection reads, as `bfc/get-file` returns it minus the
  data blob."
  [revn]
  {:id          file-id
   :name        "graph sync parity fixture"
   :revn        revn
   :version     70
   :features    #{"components/v2"}
   :created-at  (ct/inst "2026-01-01T00:00:00Z")
   :modified-at (ct/inst "2026-01-02T00:00:00Z")})

(defn- base-data
  []
  (binding [ffeat/*current* #{"components/v2"}]
    (ctf/make-file-data file-id page-id)))

(defn- shape
  [id type attrs]
  (cts/setup-shape (merge {:id        id
                           :type      type
                           :frame-id  uuid/zero
                           :parent-id uuid/zero}
                          attrs)))

(def ^:private changes
  "One change of every kind the sync path claims to support that this fixture
  can exercise, in the order an editing session would emit them.

  Four siblings in one container, then a reorder, a reparent, and a delete:
  sibling order is where the two paths are easiest to get wrong, because the
  stored `:shapes` list and `IsChildOf.position` run opposite ways."
  [{:type :add-obj :page-id page-id :id frame-id
    :parent-id uuid/zero :frame-id uuid/zero
    :obj (shape frame-id :frame {:name "Board" :width 400 :height 300})}

   {:type :add-obj :page-id page-id :id rect-id
    :parent-id frame-id :frame-id frame-id
    :obj (shape rect-id :rect {:name "Rect" :parent-id frame-id :frame-id frame-id
                               :width 100 :height 50})}

   {:type :add-obj :page-id page-id :id circ-id
    :parent-id frame-id :frame-id frame-id
    :obj (shape circ-id :circle {:name "Circle" :parent-id frame-id :frame-id frame-id
                                 :width 40 :height 40})}

   {:type :add-obj :page-id page-id :id text-id
    :parent-id frame-id :frame-id frame-id
    :obj (shape text-id :text {:name "Label" :parent-id frame-id :frame-id frame-id})}

   {:type :add-obj :page-id page-id :id rect2-id
    :parent-id frame-id :frame-id frame-id
    :obj (shape rect2-id :rect {:name "Rect two" :parent-id frame-id :frame-id frame-id
                                :width 20 :height 20})}

   ;; A rename, and two attributes whose values are falsy: `blocked false` and
   ;; `opacity 0` are values, not absences, on both paths.
   {:type :mod-obj :page-id page-id :id rect-id
    :operations [{:type :set :attr :name :val "Renamed rect"}
                 {:type :set :attr :blocked :val false}
                 {:type :set :attr :opacity :val 0}]}

   ;; Reorder inside the same container: the edge keeps its endpoints and
   ;; every sibling it passes has to move.
   {:type :mov-objects :page-id page-id :parent-id frame-id :index 0 :shapes [circ-id]}

   ;; Reparent to the page's root frame: the edge moves, and so do the
   ;; shape's own `parent_id` and `frame_id`.
   {:type :mov-objects :page-id page-id :parent-id uuid/zero :index 0 :shapes [text-id]}

   ;; Delete with survivors: the gap in the sibling numbering has to close.
   {:type :del-obj :page-id page-id :id rect-id}

   {:type :add-page :id page2-id :name "Page two"}
   {:type :mod-page :id page-id :name "Page one, renamed"}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; projecting and reading back
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- load-graph!
  "Create the schema on `conn`, project `data` into it, run the transforms.

  Returns the projection, which is also what the sync index is built from."
  [conn data file]
  (let [projection (projection.document/projection-data data file)]
    (ladybug/exec-on-connection! conn (nodes/ddl-statements))
    (arrow/with-allocator!
      (fn [allocator] (arrow/load-projection! conn projection allocator)))
    (projection.transforms/apply-transforms! nil conn data file)
    projection))

(defn- rel-tables
  [conn]
  (mapv first (:rows (ladybug/query-on-connection!
                      conn "CALL show_tables() WHERE type = 'REL' RETURN name;"
                      :max-rows 1000))))

(defn- rel-properties
  "Property names on rel table `rel`, in catalog order."
  [conn rel]
  (mapv (comp str second)
        (:rows (ladybug/query-on-connection!
                conn (str "CALL table_info('" rel "') RETURN *;")
                :max-rows 1000))))

(defn- node-rows
  [conn table]
  (:rows (ladybug/query-on-connection!
          conn (str "MATCH (n:" (nodes/match-label table) ") RETURN n.* ORDER BY n.id;")
          :max-rows 100000)))

(defn- edge-rows
  [conn rel props]
  (let [returns (into ["a.id" "b.id"] (map #(str "r.`" % "`")) props)]
    (:rows (ladybug/query-on-connection!
            conn (str "MATCH (a)-[r:`" rel "`]->(b) "
                      "RETURN " (clojure.string/join ", " returns) " "
                      "ORDER BY a.id, b.id;")
            :max-rows 100000))))

(defn- keyed-rows
  "Rows as `{key {column value}}`, so a difference names a row and a column.

  Values are stringified: both connections hand a value back through the same
  reader, so any difference in the strings is a difference in the graph."
  [columns key-columns rows]
  (into {}
        (map (fn [row]
               (let [cells (zipmap columns (map str row))]
                 [(mapv cells key-columns) cells])))
        rows))

(defn- snapshot
  "Every node row and every edge row in the database, keyed by table."
  [conn]
  {:nodes (into {}
                (map (fn [{:keys [table]}]
                       (let [columns (nodes/columns table)]
                         [table (keyed-rows columns ["id"] (node-rows conn table))])))
                nodes/node-types)
   :edges (into {}
                (map (fn [rel]
                       (let [columns (into ["from" "to"] (rel-properties conn rel))]
                         [rel (keyed-rows columns ["from" "to"]
                                          (edge-rows conn rel (rel-properties conn rel)))])))
                (rel-tables conn))})

(defn- row-diff
  [rows-a rows-b]
  (into {}
        (for [k     (sort (into #{} (concat (keys rows-a) (keys rows-b))))
              :let  [a (get rows-a k)
                     b (get rows-b k)]
              :when (not= a b)]
          [k (cond
               (nil? a) {:only-in :rebuilt}
               (nil? b) {:only-in :synced}
               :else    (into {}
                              (for [c     (sort (into #{} (concat (keys a) (keys b))))
                                    :when (not= (get a c) (get b c))]
                                [c {:synced (get a c) :rebuilt (get b c)}])))])))

(defn- diff
  "Where the two snapshots disagree, down to the row and the column."
  [a b]
  (into {}
        (for [kind  [:nodes :edges]
              table (sort (into #{} (concat (keys (get a kind)) (keys (get b kind)))))
              :let  [d (row-diff (get-in a [kind table]) (get-in b [kind table]))]
              :when (seq d)]
          [[kind table] d])))

(defn- with-two-connections
  [f]
  (ladybug/with-connection! ":memory:"
    (fn [conn-a]
      (ladybug/with-connection! ":memory:"
        (fn [conn-b]
          (f conn-a conn-b))))))

(defn- round-trip
  "Sync `change-list` into A, rebuild the same file into B, return the diff."
  [change-list]
  (let [data0 (base-data)
        data1 (cfc/process-changes data0 change-list)
        revn1 (inc base-revn)]
    (with-two-connections
      (fn [conn-a conn-b]
        (let [projection (load-graph! conn-a data0 (file-row base-revn))
              index      (sync/build-index file-id base-revn projection)
              result     (sync/apply-changes! conn-a index change-list revn1)]
          (load-graph! conn-b data1 (file-row revn1))
          {:diff    (diff (snapshot conn-a) (snapshot conn-b))
           :applied (:applied result)
           :skipped (:skipped result)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest every-change-in-the-list-is-supported
  (let [{:keys [applied skipped]} (round-trip changes)]
    (t/is (empty? skipped)
          (str "the fixture must exercise the sync path, not the skip path: " (pr-str skipped)))
    (t/is (= (count changes) (count applied)))))

(t/deftest synced-graph-equals-rebuilt-graph
  (let [{:keys [diff]} (round-trip changes)]
    (t/is (empty? diff)
          (str "cold projection and sync replay disagree on "
               (pr-str (keys diff)) "\n" (pr-str diff)))))

(t/deftest the-diff-catches-an-injected-sync-bug
  ;; The round trip is only worth running if it fails when sync is wrong.
  ;; `apply-mov-objects` maintains `IsChildOf`; drop the change from the list
  ;; sync sees, keep it in the list the file sees, and the edge must differ.
  (let [data0   (base-data)
        data1   (cfc/process-changes data0 changes)
        crippled (remove #(= :mov-objects (:type %)) changes)
        revn1   (inc base-revn)
        result  (with-two-connections
                  (fn [conn-a conn-b]
                    (let [projection (load-graph! conn-a data0 (file-row base-revn))
                          index      (sync/build-index file-id base-revn projection)]
                      (sync/apply-changes! conn-a index crippled revn1)
                      (load-graph! conn-b data1 (file-row revn1))
                      (diff (snapshot conn-a) (snapshot conn-b)))))]
    (t/is (contains? result [:edges "IsChildOf"])
          "a sync that skips a reparent must show up as an IsChildOf difference")))
