;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.shapes-builder-test
  (:require
   [app.common.files.shapes-builder :as sb]
   [clojure.test :as t]))

;; Regression for https://github.com/penpot/penpot/issues/7869.
;; ``parse-svg-element`` used to derive the shape name from
;; ``(or (:id attrs) (tag->name tag))`` which dropped Inkscape-authored
;; labels. ``tubax/xml->clj`` (the SVG parser the rest of the import
;; pipeline already feeds these maps to) keeps namespaced attributes as
;; ``:prefix:name`` keywords — same shape the codebase already reads
;; ``:xlink:href`` from in this file (line 134) and in
;; ``app.common.svg``.

(t/deftest resolve-element-name-prefers-inkscape-label
  (t/is (= "Layer 1"
           (sb/resolve-element-name :g {:inkscape:label "Layer 1"
                                        :id "g1234"}))))

(t/deftest resolve-element-name-prefers-sodipodi-label-when-no-inkscape-label
  (t/is (= "phone-icon"
           (sb/resolve-element-name :path {:sodipodi:label "phone-icon"
                                           :id "path5678"}))))

(t/deftest resolve-element-name-falls-back-to-id-when-no-label-namespace
  (t/is (= "manual-id"
           (sb/resolve-element-name :rect {:id "manual-id"}))))

(t/deftest resolve-element-name-falls-back-to-tag-name-when-no-id-and-no-label
  ;; The tag->name mapping returns generic names for known SVG element
  ;; tags. Asserting on the call result here (rather than a hardcoded
  ;; string) keeps the test stable if the tag->name mapping is updated.
  (t/is (some? (sb/resolve-element-name :rect {})))
  (t/is (string? (sb/resolve-element-name :rect {}))))

(t/deftest resolve-element-name-inkscape-label-wins-over-sodipodi-and-id
  ;; Both label conventions and an id present together; the priority is
  ;; inkscape > sodipodi > id > tag, matching the order operators expect
  ;; (Inkscape's own UI shows ``inkscape:label`` as the canonical name).
  (t/is (= "user-name"
           (sb/resolve-element-name :g {:inkscape:label "user-name"
                                        :sodipodi:label "stale-label"
                                        :id "g1"}))))

(t/deftest resolve-element-name-empty-attrs-uses-tag-fallback
  (t/is (some? (sb/resolve-element-name :path {}))))

;; Regression for https://tree.taiga.io/project/penpot/issue/8277
;; stroke-linecap and stroke-linejoin on the SVG root must be inherited
;; by child path shapes, and stroke-cap-start/end must be stored inside
;; the stroke entry (not at the shape top level).
(t/deftest svg-root-stroke-linecap-inherited-to-path-shapes
  (let [svg-data {:name "icon"
                  :tag :svg
                  :attrs {:xmlns "http://www.w3.org/2000/svg"
                          :width "24" :height "24"
                          :viewBox "0 0 24 24"
                          :fill "none"
                          :stroke "currentColor"
                          :stroke-width "2"
                          :stroke-linecap "round"
                          :stroke-linejoin "round"}
                  :content [{:tag :line
                             :attrs {:x1 "12" :y1 "8" :x2 "12" :y2 "12"}
                             :content []}]}
        [_root children] (sb/create-svg-shapes svg-data {:x 0 :y 0} {} nil nil #{} false)
        path-shapes      (filter #(= :path (:type %)) children)]

    ;; At least one path shape was created from the <line> element
    (t/is (seq path-shapes))

    (doseq [shape path-shapes]
      (let [stroke (first (:strokes shape))]
        ;; svg-attrs must carry stroke-linecap and stroke-linejoin for the renderers
        (t/is (= "round" (get-in shape [:svg-attrs :strokeLinecap])))
        (t/is (= "round" (get-in shape [:svg-attrs :strokeLinejoin])))

        ;; stroke-cap-start/end must be inside the stroke entry, not at shape level
        (t/is (= :round (:stroke-cap-start stroke))
              "stroke-cap-start should be in the stroke entry")
        (t/is (= :round (:stroke-cap-end stroke))
              "stroke-cap-end should be in the stroke entry")
        (t/is (nil? (:stroke-cap-start shape))
              "stroke-cap-start must NOT be at the shape top level")

        ;; stroke-style is not set at import time (nil means default solid rendering)
        (t/is (nil? (:stroke-style stroke)))))))
