;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0035-test
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.rect :as grc]
   [app.common.types.file :as ctf]
   [app.common.types.objects-map :as omap]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; ======================================================================
;; 0035-fix-dangling-children
;; A shape can list a child id that is not present in the `:objects` map
;; of the page or component owning it (a dangling reference). Any
;; traversal of the shape tree trips on it, and the file becomes
;; unopenable. The migration drops those ids and keeps every shape that
;; really exists.
;; ======================================================================

(def ^:private migration "0035-fix-dangling-children")

(defn- make-root
  [children]
  (cts/setup-shape {:id uuid/zero
                    :type :frame
                    :name "Root"
                    :shapes children}))

(defn- make-frame
  [frame-id child-ids]
  (-> (cts/setup-shape {:id frame-id :type :frame :name "Frame"})
      (assoc :shapes child-ids)))

(defn- page-data
  [page-id objects]
  {:pages-index {page-id {:id page-id :objects objects}}})

(defn- component-data
  [component-id objects]
  {:components {component-id {:id component-id :objects objects}}})

(t/deftest removes-the-dangling-children-of-a-page
  (let [page-id   (uuid/next)
        frame-id  (uuid/next)
        child-id  (uuid/next)
        frame     (make-frame frame-id [child-id])
        data      (page-data page-id {uuid/zero (make-root [frame-id child-id])
                                      frame-id  frame})
        migrated  (cfm/migrate-data data migration)
        objects'  (get-in migrated [:pages-index page-id :objects])]

    (t/is (= [frame-id] (get-in objects' [uuid/zero :shapes]))
          "the dangling id is removed from the root children list")
    (t/is (= [] (get-in objects' [frame-id :shapes]))
          "the dangling id is removed from the frame children list")
    (t/is (= 2 (count objects'))
          "every shape that exists is kept")
    (t/is (some? (get objects' frame-id))
          "the frame itself is kept")))

(t/deftest removes-the-dangling-children-of-a-component
  (let [component-id (uuid/next)
        frame-id     (uuid/next)
        child-id     (uuid/next)
        frame        (cts/setup-shape {:id frame-id :type :frame :name "Frame"})
        data         (component-data component-id {uuid/zero (make-root [frame-id child-id])
                                                   frame-id frame})
        migrated     (cfm/migrate-data data migration)
        objects'     (get-in migrated [:components component-id :objects])]

    (t/is (= [frame-id] (get-in objects' [uuid/zero :shapes]))
          "the dangling id is removed from the children list")
    (t/is (= 2 (count objects'))
          "every shape that exists is kept")
    (t/is (= frame (get objects' frame-id))
          "the existing shape is left untouched")))

(t/deftest migration-works-with-an-objects-map
  ;; The backend stores the page :objects as an ObjectsMap (the lazy
  ;; representation enabled by the fdata/objects-map feature). It is an
  ;; IPersistentMap that does not implement IFn on the JVM: a migration
  ;; must consult it with contains?/get, never by calling it as a
  ;; function.
  (let [page-id   (uuid/next)
        frame-id  (uuid/next)
        child-id  (uuid/next)
        frame     (make-frame frame-id [child-id])
        objects   (omap/wrap {uuid/zero (make-root [frame-id child-id])
                              frame-id  frame})
        data      (page-data page-id objects)
        migrated  (cfm/migrate-data data migration)
        objects'  (get-in migrated [:pages-index page-id :objects])]

    (t/is (= [frame-id] (get-in objects' [uuid/zero :shapes]))
          "the dangling id is removed with the ObjectsMap representation")
    (t/is (= [] (get-in objects' [frame-id :shapes]))
          "the frame children list is pruned the same way")))

(t/deftest keeps-the-order-and-the-shapes-of-a-valid-tree
  (let [page-id  (uuid/next)
        frame-id (uuid/next)
        rect-id  (uuid/next)
        frame    (cts/setup-shape {:id frame-id :type :frame :name "Frame"})
        rect     (cts/setup-shape {:id rect-id :type :rect :name "Rect"})
        data     (page-data page-id {uuid/zero (make-root [frame-id rect-id])
                                     frame-id  frame
                                     rect-id   rect})
        migrated (cfm/migrate-data data migration)
        objects' (get-in migrated [:pages-index page-id :objects])]

    (t/is (= [frame-id rect-id] (get-in objects' [uuid/zero :shapes]))
          "the children list is kept as it was")
    (t/is (= data migrated)
          "a tree without dangling references is not modified")))

(t/deftest leaves-shapes-with-empty-children-alone
  (let [page-id  (uuid/next)
        frame    (cts/setup-shape {:id (uuid/next) :type :frame :name "Frame"})
        data     (page-data page-id {uuid/zero (make-root [])
                                     (:id frame) frame})
        migrated (cfm/migrate-data data migration)]

    (t/is (= data migrated)
          "empty children vectors are left alone")))

(t/deftest leaves-shapes-without-children-key-alone
  (let [page-id  (uuid/next)
        rect-id  (uuid/next)
        rect     (cts/setup-shape {:id rect-id :type :rect :name "Rect"})
        data     (page-data page-id {uuid/zero (make-root [])
                                     rect-id rect})
        migrated (cfm/migrate-data data migration)]

    (t/is (= data migrated)
          "shapes without a children key are left alone")
    (t/is (not (contains? (get-in migrated [:pages-index page-id :objects rect-id]) :shapes))
          "no children key is created on the shape")))

(t/deftest migration-is-idempotent
  (let [page-id  (uuid/next)
        child-id (uuid/next)
        data     (page-data page-id {uuid/zero (make-root [child-id])})
        migrated (cfm/migrate-data data migration)]

    (t/is (= migrated (cfm/migrate-data migrated migration))
          "applying the migration twice does not change the result")))

(t/deftest file-with-dangling-children-passes-the-whole-migration
  (let [file-id     (uuid/next)
        page-id     (uuid/next)
        frame-id    (uuid/next)
        missing-id  (uuid/next)
        frame       (make-frame frame-id [missing-id])
        base        (ctf/make-file-data file-id page-id)
        data        (-> base
                        (assoc-in [:pages-index page-id :objects uuid/zero]
                                  (make-root [frame-id missing-id]))
                        (assoc-in [:pages-index page-id :objects frame-id] frame))
        ;; The file reached the state before 0033 ran, so 0033, 0034 and
        ;; 0035 are all pending, exactly as in the reported failure.
        file        {:id file-id
                     :data data
                     :migrations (disj cfm/available-migrations
                                       "0033-normalize-main-instance-names"
                                       "0034-fix-path-content-offset"
                                       migration)}]

    (t/is (cfm/need-migration? file)
          "the file is scheduled for migration")

    (let [migrated (cfm/migrate-file file {})
          objects' (get-in migrated [:data :pages-index page-id :objects])]

      (t/is (contains? (:migrations migrated) migration)
            "the migration is recorded as applied")
      (t/is (not (contains? objects' nil))
            "the migrated data has no nil shape entry")
      (t/is (= [frame-id] (get-in objects' [uuid/zero :shapes]))
            "the dangling reference of the root is removed")
      (t/is (= [] (get-in objects' [frame-id :shapes]))
            "the dangling reference of the frame is removed")
      (t/is (some? (get objects' frame-id))
            "the frame that exists is kept"))))

;; ======================================================================
;; 0035-fix-shape-svg-attrs-and-defs
;; Stored svg attribute keys are kebab-case (issue #11947). Old birth and
;; the cleaner era stored camelCase React prop names, which the v3 reader
;; kebab-ized on import beyond repair. This migration converts once, with
;; the same birth transforms: `:svg-attrs` and `:svg-defs` node attrs go
;; through the whitelist+kebab transform, svg-raw `:content` attrs are
;; kebab-ized spelling-only. It replaces "0021-fix-shape-svg-attrs" (id
;; withdrawn): files that recorded it migrate again through this one.
;; ======================================================================

(def ^:private migration-id
  "0035-fix-shape-svg-attrs-and-defs")

(defn- make-shape
  [shape-id shape]
  (assoc shape :id shape-id))

(defn- camel-shape
  [shape-id]
  (make-shape shape-id
              {:type :rect
               :svg-attrs {:fillRule "evenodd"
                           :stroke-width "2"
                           :stroke-style "dotted"
                           :data-foo "drop-me"}}))

(defn- camel-defs
  []
  {"g1" {:tag :linearGradient
         :attrs {:id "g1"
                 :gradientUnits "userSpaceOnUse"
                 :xlink:href "#base"
                 :data-x "drop-me"}
         :content [{:tag :stop
                    :attrs {:offset "0"
                            :stopColor "#ffffff"}
                    :content []}]}})

(t/deftest migration-0035-converts-camel-svg-attrs-to-kebab-in-pages
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (camel-shape shape-id)}}}}
        data'    (cfm/migrate-data data migration-id)
        attrs    (get-in data' [:pages-index page-id :objects shape-id :svg-attrs])]
    (t/is (= "evenodd" (:fill-rule attrs)))
    (t/is (= "2" (:stroke-width attrs)))
    (t/is (= "dotted" (:stroke-style attrs)) "penpot-extra-attrs survive")
    (t/is (not (contains? attrs :data-foo)) "unknown attrs are dropped")
    (t/is (not (contains? attrs :fillRule)) "no camel keys survive")))

(t/deftest migration-0035-converts-camel-svg-attrs-to-kebab-in-components
  (let [shape-id     (uuid/next)
        component-id (uuid/next)
        data         {:components
                      {component-id
                       {:objects
                        {shape-id (camel-shape shape-id)}}}}
        data'        (cfm/migrate-data data migration-id)
        attrs        (get-in data' [:components component-id :objects shape-id :svg-attrs])]
    (t/is (= "evenodd" (:fill-rule attrs)))
    (t/is (not (contains? attrs :fillRule)) "no camel keys survive")))

(t/deftest migration-0035-normalizes-def-node-attrs
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-shape shape-id
                                          {:type :rect
                                           :svg-defs (camel-defs)})}}}}
        data'    (cfm/migrate-data data migration-id)
        grad     (get-in data' [:pages-index page-id :objects shape-id :svg-defs "g1"])]
    (t/is (= :linearGradient (:tag grad)) "tags are untouched")
    (t/is (= "g1" (get-in grad [:attrs :id])) ":id survives")
    (t/is (= "userSpaceOnUse" (get-in grad [:attrs :gradient-units])))
    (t/is (= "#base" (get-in grad [:attrs :xlink-href])))
    (t/is (not (contains? (:attrs grad) :data-x)) "unknown def attrs are dropped")
    (t/is (= "#ffffff" (get-in grad [:content 0 :attrs :stop-color]))
          "nested stops are normalized")))

(t/deftest migration-0035-kebabizes-content-spelling-only
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        raw-id   (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-shape shape-id
                                          {:type :path
                                           :content [{:marker "untouched"}]})
                     raw-id (make-shape raw-id
                                        {:type :svg-raw
                                         :content {:tag :g
                                                   :attrs {:strokeWidth "2"
                                                           :data-foo "keep-me"}
                                                   :content [{:tag :path
                                                              :attrs {:strokeLinecap "round"}
                                                              :content []}]}})}}}}
        data'    (cfm/migrate-data data migration-id)
        path     (get-in data' [:pages-index page-id :objects shape-id])
        raw      (get-in data' [:pages-index page-id :objects raw-id])
        content  (:content raw)]
    (t/is (= [{:marker "untouched"}] (:content path))
          "non-svg-raw content is untouched")
    (t/is (= "2" (get-in content [:attrs :stroke-width])))
    (t/is (= "keep-me" (get-in content [:attrs :data-foo]))
          "unknown content keys survive")
    (t/is (= "round" (get-in content [:content 0 :attrs :stroke-linecap])))))

(defn- string-tag-defs
  []
  {:a {:tag "mask"
       :attrs {:id "a" :maskUnits "userSpaceOnUse"}
       :content [{:tag "rect"
                  :attrs {:width "10" :height "10" :fill "#FFF"}
                  :content []}]}})

(defn- string-tag-content
  []
  {:tag "g"
   :attrs {}
   :content [{:tag "text"
              :attrs {:fontSize "12"}
              :content ["Hi"]}
             {:tag "style"
              :attrs nil
              :content [".a{fill:red}"]}]})

(t/deftest migration-0035-keywordizes-string-tags
  (let [shape-id (uuid/next)
        raw-id   (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-shape shape-id
                                          {:type :rect
                                           :svg-defs (string-tag-defs)})
                     raw-id   (make-shape raw-id
                                          {:type :svg-raw
                                           :content (string-tag-content)})}}}}
        data'    (cfm/migrate-data data migration-id)
        mask     (get-in data' [:pages-index page-id :objects shape-id :svg-defs :a])
        content  (get-in data' [:pages-index page-id :objects raw-id :content])]
    (t/is (= :mask (:tag mask)))
    (t/is (= :rect (get-in mask [:content 0 :tag])) "nested def tags too")
    (t/is (= "userSpaceOnUse" (get-in mask [:attrs :mask-units])))
    (t/is (= :g (:tag content)))
    (t/is (= :text (get-in content [:content 0 :tag])) "nested content tags too")
    (t/is (= ["Hi"] (get-in content [:content 0 :content])))
    (t/is (= {} (get-in content [:content 1 :attrs])) "nil attrs become an empty map")
    (t/is (= data' (cfm/migrate-data data' migration-id)) "re-running is stable")))

(t/deftest migration-0035-string-tag-file-passes-file-validation
  ;; `migrate-file` validates the whole file data after migrating: a file
  ;; with string node tags or nil node attrs must come out valid, or it
  ;; cannot be opened.
  (let [rect-id (uuid/next)
        raw-id  (uuid/next)
        file    (ctf/make-file {:name "String svg tags"})
        page-id (first (get-in file [:data :pages]))
        rect    (-> (cts/setup-shape {:id rect-id :type :rect :name "rect"})
                    (assoc :svg-defs (string-tag-defs)))
        raw     (-> (cts/setup-shape {:id raw-id :type :svg-raw :name "raw"})
                    (assoc :content (string-tag-content)))
        file    (-> file
                    (assoc :migrations (disj cfm/available-migrations migration-id))
                    (update-in [:data :pages-index page-id :objects] assoc rect-id rect raw-id raw)
                    (update-in [:data :pages-index page-id :objects uuid/zero :shapes] conj rect-id raw-id))
        file'   (cfm/migrate-file file {})]
    (t/is (= :mask (get-in file' [:data :pages-index page-id :objects rect-id :svg-defs :a :tag])))
    (t/is (= :g (get-in file' [:data :pages-index page-id :objects raw-id :content :tag])))))

(t/deftest migration-0035-turns-plain-map-svg-transform-into-matrix
  ;; The svg defs renderer multiplies `:svg-transform` as a matrix; a
  ;; plain map there renders as `matrix(NaN, ...)`.
  (let [plain-id  (uuid/next)
        matrix-id (uuid/next)
        page-id   (uuid/next)
        data      {:pages-index
                   {page-id
                    {:objects
                     {plain-id  (make-shape plain-id
                                            {:type :path
                                             :svg-transform {:a 1.0 :b 0.0 :c 0.0 :d 1.0 :e 10.0 :f 20.0}})
                      matrix-id (make-shape matrix-id
                                            {:type :path
                                             :svg-transform (gmt/matrix 2 0 0 2 0 0)})}}}}
        data'     (cfm/migrate-data data migration-id)
        plain'    (get-in data' [:pages-index page-id :objects plain-id :svg-transform])
        matrix'   (get-in data' [:pages-index page-id :objects matrix-id :svg-transform])]
    (t/is (gmt/matrix? plain'))
    (t/is (= (gmt/matrix 1 0 0 1 10 20) plain'))
    (t/is (= (gmt/matrix 2 0 0 2 0 0) matrix') "matrices are left as they are")
    (t/is (= data' (cfm/migrate-data data' migration-id)) "re-running is stable")))

(t/deftest migration-0035-leaves-kebab-files-equal
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-shape shape-id
                                          {:type :rect
                                           :svg-attrs {:fill-rule "evenodd"
                                                       :stroke-width "2"}
                                           :svg-defs {"g1" {:tag :linearGradient
                                                            :attrs {:id "g1"
                                                                    :gradient-units "userSpaceOnUse"}
                                                            :content []}}})}}}}
        data'    (cfm/migrate-data data migration-id)]
    (t/is (= data data') "kebab is a fixed point")))

(t/deftest migration-0035-is-idempotent
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (camel-shape shape-id)}}}}
        data'    (cfm/migrate-data data migration-id)]
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0035-normalizes-flat-svg-viewbox-to-rect
  ;; Carried over from the withdrawn 0021: legacy files hold
  ;; `:svg-viewbox` as a plain map instead of a rect record.
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-shape shape-id
                                          {:type :rect
                                           :svg-viewbox {:x 1 :y 2 :width 3 :height 4}})}}}}
        data'    (cfm/migrate-data data migration-id)
        viewbox  (get-in data' [:pages-index page-id :objects shape-id :svg-viewbox])]
    (t/is (grc/rect? viewbox) "flat viewbox becomes a rect record")
    (t/is (= 1 (:x viewbox)))
    (t/is (= 2 (:y viewbox)))
    (t/is (= 3 (:width viewbox)))
    (t/is (= 4 (:height viewbox)))
    (t/is (= data' (cfm/migrate-data data' migration-id))
          "re-running is stable")))

(t/deftest migration-0035-passes-non-maps-through
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-shape shape-id
                                          {:type :rect
                                           :svg-attrs "junk"
                                           :svg-defs "junk"})}}}}
        data'    (cfm/migrate-data data migration-id)
        shape    (get-in data' [:pages-index page-id :objects shape-id])]
    (t/is (= "junk" (:svg-attrs shape)) "non-maps reach validation untouched")
    (t/is (= "junk" (:svg-defs shape)))))

(t/deftest migration-0035-junk-viewbox-becomes-default-rect
  ;; Unlike `:svg-attrs`/`:svg-defs` (passed through to validation),
  ;; `:svg-viewbox` goes through `make-rect`, which does not throw on
  ;; junk: it falls back to the default rect. Inherited from 0021.
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (make-shape shape-id
                                          {:type :rect
                                           :svg-viewbox "junk"})}}}}
        data'    (cfm/migrate-data data migration-id)
        viewbox  (get-in data' [:pages-index page-id :objects shape-id :svg-viewbox])]
    (t/is (grc/rect? viewbox) "junk viewbox becomes a rect, not an error")
    (t/is (= 0 (:x viewbox)))
    (t/is (= 0.01 (:width viewbox)))))

(t/deftest migration-0035-runs-through-file-migration
  ;; A file that recorded the withdrawn "0021-fix-shape-svg-attrs" (and
  ;; everything else current) still needs migration: 0021 left
  ;; `available-migrations`, which re-arms it for 0035.
  (let [shape-id (uuid/next)
        file     (ctf/make-file {:name "Legacy camel svg attrs"})
        page-id  (first (get-in file [:data :pages]))
        shape    (-> (cts/setup-shape {:id shape-id :type :rect})
                     (assoc :svg-attrs {:fillRule "evenodd"}))
        file     (-> file
                     (assoc :migrations (-> cfm/available-migrations
                                            (disj migration-id)
                                            (conj "0021-fix-shape-svg-attrs")))
                     (assoc-in [:data :pages-index page-id :objects shape-id] shape))
        file'    (cfm/migrate-file file {})]
    (t/is (cfm/need-migration? file) "new migration detected")
    (t/is (not (cfm/need-migration? file')) "new migration recorded")
    (t/is (contains? (:migrations file') migration-id) "migration id persisted")
    (t/is (not (contains? cfm/available-migrations "0021-fix-shape-svg-attrs"))
          "the withdrawn 0021 id stays out: its camel-restoring transform must never run again")
    (t/is (= "evenodd"
             (get-in file' [:data :pages-index page-id :objects shape-id :svg-attrs :fill-rule]))
          "migration repaired file data before schema validation")))
