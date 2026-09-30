;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-migrations-0031-test
  (:require
   [app.common.files.migrations :as cfm]
   [app.common.geom.rect :as grc]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

;; 0031-fix-shape-svg-attrs-and-defs
;; Stored svg attribute keys are kebab-case (issue #11947). Old birth and
;; the cleaner era stored camelCase React prop names, which the v3 reader
;; kebab-ized on import beyond repair. This migration converts once, with
;; the same birth transforms: `:svg-attrs` and `:svg-defs` node attrs go
;; through the whitelist+kebab transform, svg-raw `:content` attrs are
;; kebab-ized spelling-only. It replaces "0021-fix-shape-svg-attrs" (id
;; withdrawn): files that recorded it migrate again through this one.

(def ^:private migration-id
  "0031-fix-shape-svg-attrs-and-defs")

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

(t/deftest migration-0031-converts-camel-svg-attrs-to-kebab-in-pages
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

(t/deftest migration-0031-converts-camel-svg-attrs-to-kebab-in-components
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

(t/deftest migration-0031-normalizes-def-node-attrs
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

(t/deftest migration-0031-kebabizes-content-spelling-only
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

(t/deftest migration-0031-leaves-kebab-files-equal
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

(t/deftest migration-0031-is-idempotent
  (let [shape-id (uuid/next)
        page-id  (uuid/next)
        data     {:pages-index
                  {page-id
                   {:objects
                    {shape-id (camel-shape shape-id)}}}}
        data'    (cfm/migrate-data data migration-id)]
    (t/is (= data' (cfm/migrate-data data' migration-id)))))

(t/deftest migration-0031-normalizes-flat-svg-viewbox-to-rect
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

(t/deftest migration-0031-passes-non-maps-through
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

(t/deftest migration-0031-runs-through-file-migration
  ;; A file that recorded the withdrawn "0021-fix-shape-svg-attrs" (and
  ;; everything else current) still needs migration: 0021 left
  ;; `available-migrations`, which re-arms it for 0031.
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
