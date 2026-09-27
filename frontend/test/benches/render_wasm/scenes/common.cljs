;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.common
  "Utilities shared by generated and saved-page scenes.

  A scene snapshot is
  `{:objects {uuid shape ...} :refs {label uuid ...}}`.

      - `:objects` is a Penpot page object map.
      - `:refs` is a snapshot-local lookup from custom labels to object uuids.

  A scene snapshot is just static data. It is independent of
  camera, operations, WASM buffers and renderer calls, and it never mutates after
  upload.

  Validation uses penpot's facilities, so that benchmark scenes follow editor
  semantics. We do check reachability from the root here because neither the
  editor validator nor file loading guarantees it.

  Authoring and scoped construction are ticket17; containers ticket18/19;
  case declarations ticket20; saved-page loading ticket21.

  Which shape types may own children is container semantics owned by
  ticket18/19; this contract only checks hierarchy consistency."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.files.validate :as cfv]
   [app.common.geom.shapes.tree-seq :as gts]
   [app.common.schema :as sm]
   [app.common.types.page :as ctp]
   [app.common.uuid :as uuid]))

(def schema:snapshot
  "Envelope schema for a scene snapshot."
  [:map {:title "RendererBenchmarkSceneSnapshot"}
   [:objects ctp/schema:objects]
   [:refs [:map-of :any ::sm/uuid]]])

(defn- fail!
  [hint data]
  (throw (ex-info (str "Invalid scene snapshot: " hint)
                  (merge {:type ::invalid-snapshot
                          :hint hint}
                         data))))

(defn- check-schema!
  [instance]
  (when-not (sm/validate schema:snapshot instance)
    (fail! "instance does not match the scene snapshot schema"
           {::sm/explain (sm/explain schema:snapshot instance)})))

(defn- check-root!
  [instance]
  (let [root (get-in instance [:objects uuid/zero])]
    (when-not (and (some? root) (cfh/root? root))
      (fail! "root object must be a frame at uuid/zero"
             {:id uuid/zero}))
    (when-not (= uuid/zero (:parent-id root))
      (fail! "root object :parent-id must be uuid/zero"
             {:id uuid/zero
              :parent-id (:parent-id root)}))
    (when-not (= uuid/zero (:frame-id root))
      (fail! "root object :frame-id must be uuid/zero"
             {:id uuid/zero
              :frame-id (:frame-id root)}))))

(defn- check-object-ids!
  [objects]
  (doseq [[id shape] objects]
    (when-not (= id (:id shape))
      (fail! "object key does not match the shape :id"
             {:key id
              :shape-id (:id shape)}))))

(defn- check-child-links!
  "Guard the reachability walk against malformed child links.

  `:shapes` must be a vector when present, and every listed child that
  exists must point back at the listing shape. This keeps each child with a
  single parent, so the descendant walk visits a forest instead of fanning
  out over shared children or looping on a cycle. Production validation
  repeats these checks for reachable shapes; this one only protects our own
  traversal order."
  [objects]
  (doseq [[id shape] objects
          :let [children (:shapes shape)]]
    (when (some? children)
      (when-not (vector? children)
        (fail! "shape :shapes must be a vector"
               {:id id}))
      (doseq [child-id children
              :let [child (get objects child-id)]
              :when (some? child)]
        (when-not (= id (:parent-id child))
          (fail! "shape :shapes lists a child with a different :parent-id"
                 {:id id
                  :child child-id}))))))

(defn- check-reachability!
  "Check the root graph through the cycle-safe descendant walk.

  Runs `check-child-links!` first, which keeps the walk linear: one parent
  per child and vector `:shapes` mean the walk cannot fan out over shared
  children or loop on a cycle.

  Detects missing child ids, objects reachable more than once (shared
  children or cycles), detached subtrees and a root listed as its own
  descendant. Runs before the production validator, whose walk follows
  `:shapes` links and would loop on a reachable cycle."
  [objects]
  (check-child-links! objects)
  (let [ids  (cfh/get-children-ids objects uuid/zero)
        seen (set ids)]

    (when (contains? seen uuid/zero)
      (fail! "root must not be a descendant of itself"
             {:id uuid/zero}))

    (doseq [id ids]
      (when-not (contains? objects id)
        (fail! "shape :shapes references a missing object"
               {:child id})))

    (when-let [dup (first (for [[id n] (frequencies ids) :when (> n 1)] id))]
      (fail! "an object is reachable more than once"
             {:id dup}))

    (doseq [id (keys objects)
            :when (not= id uuid/zero)]
      (when-not (contains? seen id)
        (fail! "object is not reachable from the root"
               {:id id})))))

;; Captured-page policy (tickets 02/26; implementation pending): extraction
;; maps canonical component/detach-shape over every selected-page shape,
;; without resolving component libraries, then rejects remaining unsupported
;; references/resources before validation. Warn and record when detachment
;; changes shapes; do not carry document context or use editor detach flows.
;; Thus the empty file/libraries below describe a standalone snapshot, not
;; support for arbitrary component-bearing pages. Move reusable checks to
;; shared .cljc code for JVM extraction and CLJS loading; do not copy them.

(defn- check-referential-integrity!
  "Run the production referential validation over the reachable graph.

  `cfv/validate-shape` checks parent/child links, frame ids and geometry
  with editor semantics. The file and page only carry the uuids the
  validation error schema requires; scenes contain no component shapes.
  Unreachable objects never reach this walk; `check-reachability!` owns
  them."
  [instance]
  (let [file   {:id uuid/zero :data {}}
        page   {:id uuid/zero :objects (:objects instance)}
        errors (cfv/validate-shape uuid/zero file page {})]
    (when-let [{:keys [hint] :as error} (first errors)]
      (fail! hint (select-keys error [:code :shape-id :args])))))

(defn- check-refs!
  [instance]
  (let [objects (:objects instance)]
    (doseq [[label target] (:refs instance)]
      (when-not (contains? objects target)
        (fail! "ref target is not present in :objects"
               {:label label
                :target target})))))

(defn validate!
  "Validates a scene snapshot and returns it unchanged.

  Throws `ex-info` with `{:type ::invalid-snapshot :hint ...}` when the
  snapshot is invalid. Schema failures also carry `::sm/explain`.

  Reachability runs before the production validator, because that walk
  follows `:shapes` links and would not terminate on a reachable cycle.

  Explicit `throw`s are used instead of assertions so the checks survive
  elided assertions."
  [instance]
  (check-schema! instance)
  (let [objects (:objects instance)]
    (check-root! instance)
    (check-object-ids! objects)
    (check-reachability! objects)
    (check-referential-integrity! instance)
    (check-refs! instance))
  instance)

(defn upload-order
  "Returns the instance objects as a vector in parent-before-child order.

  The root comes first and each shape's `:shapes` order is followed, using
  the shared tree traversal. That traversal reverses children of flex
  containers marked reverse; upload order carries no renderer semantics
  because a shape's child list is uploaded from its own `:shapes` vector.

  Assumes a validated instance: every child id exists."
  [instance]
  (vec (gts/get-children-seq uuid/zero (:objects instance))))

(defn ref-id
  "Resolves an author label to the referenced object uuid, or nil."
  [instance label]
  (get-in instance [:refs label]))
