;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.shapes-builder-test
  (:require
   [app.common.files.shapes-builder :as sb]
   [app.common.svg :as csvg]
   [app.common.types.color :as clr]
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
        (t/is (= "round" (get-in shape [:svg-attrs :stroke-linecap])))
        (t/is (= "round" (get-in shape [:svg-attrs :stroke-linejoin])))

        ;; stroke-cap-start/end must be inside the stroke entry, not at shape level
        (t/is (= :round (:stroke-cap-start stroke))
              "stroke-cap-start should be in the stroke entry")
        (t/is (= :round (:stroke-cap-end stroke))
              "stroke-cap-end should be in the stroke entry")
        (t/is (nil? (:stroke-cap-start shape))
              "stroke-cap-start must NOT be at the shape top level")

        ;; stroke-style is not set at import time (nil means default solid rendering)
        (t/is (nil? (:stroke-style stroke)))))))

;; Kebab storage convention for https://github.com/penpot/penpot/issues/11947.
;; Stored attribute keys (`:svg-attrs`, `:svg-defs` node `:attrs`,
;; `:content` `:attrs`) are always kebab-case: birth normalizes any
;; outside spelling, so export/import converges to the same state.

(t/deftest attrs->kebab-props-whitelists-trims-and-kebabizes
  (let [props (csvg/attrs->kebab-props {:fillRule "evenodd"
                                        :stroke-width " 2 "
                                        :viewBox "0 0 10 10"
                                        :stdDeviation "3"
                                        :xlink:href "#g"
                                        :href "#g"
                                        :stroke-style "dotted"
                                        :style "fill:red;stroke-width:2"
                                        :data-foo "drop-me"})]
    (t/is (= "evenodd" (:fill-rule props)))
    (t/is (= "2" (:stroke-width props)) "values are trimmed")
    (t/is (= "0 0 10 10" (:view-box props)))
    (t/is (= "3" (:std-deviation props)))
    (t/is (= "#g" (:xlink-href props)) "colon is lost through the camel roundtrip")
    (t/is (= "#g" (:href props)))
    (t/is (= "dotted" (:stroke-style props)) "penpot-extra-attrs survive")
    (t/is (= {:fill "red" :stroke-width "2"} (:style props)) "style maps are kebab too")
    (t/is (not (contains? props :data-foo)) "unknown attrs are dropped")
    (t/is (not (contains? props :fillRule)) "no camel keys survive")
    (t/is (= props (csvg/attrs->kebab-props props)) "kebab is a fixed point")))

(t/deftest attrs->props-keeps-extra-attrs-in-source-spelling
  ;; Extra attrs skip camelization so React passes them to the DOM
  ;; silently (lowercase + hyphenated: no unknown-prop warning).
  (t/is (= {:stroke-style "dotted" :fillRule "evenodd"}
           (csvg/attrs->props {:stroke-style "dotted" :fillRule "evenodd"})))
  (t/is (= {:stroke-style "dotted"}
           (:style (csvg/attrs->props {:style "stroke-style:dotted"}))))
  (t/is (= {:stroke-style "dotted"}
           (:style (csvg/attrs->kebab-props {:style "stroke-style:dotted"})))
        "birth agrees: kebab stays kebab"))

(t/deftest collect-images-resolves-href-and-xlink-href
  ;; Both href spellings resolve: plain `:href` (SVG2 style, kept by the
  ;; whitelist so gradients and images survive birth) and `:xlink:href`.
  (let [svg-data {:tag :svg
                  :attrs {}
                  :content [{:tag :image
                             :attrs {:xlink:href "#a" :width "10" :height "20"}
                             :content []}
                            {:tag :image
                             :attrs {:href "#b" :width "30" :height "40"}
                             :content []}]}]
    (t/is (= [{:href "#a" :width 10 :height 20}
              {:href "#b" :width 30 :height 40}]
             (csvg/collect-images svg-data))))
  (t/is (= {:href "#b"} (csvg/attrs->kebab-props {:href "#b"}))
        "plain href survives the whitelist"))

(t/deftest birth-stores-svg-attrs-as-kebab
  (let [svg-data {:name "kebab"
                  :tag :svg
                  :attrs {:width "100" :height "100" :viewBox "0 0 100 100"}
                  :content [{:tag :rect
                             :attrs {:x "10" :y "10" :width "80" :height "80"
                                     :fill "red"
                                     :fillRule "evenodd"
                                     :stroke-width "2"
                                     :stroke-style "dotted"
                                     :data-foo "drop-me"}
                             :content []}]}
        [_ children] (sb/create-svg-shapes svg-data {:x 0 :y 0} {} nil nil #{} false)
        ;; NOTE: `create-svg-shapes` prepends a hidden background rect, skip it
        rect         (first (filter #(and (= :rect (:type %)) (not (:hidden %))) children))]
    (t/is (some? rect))
    (let [attrs (:svg-attrs rect)]
      (t/is (= "evenodd" (:fill-rule attrs)))
      (t/is (= "2" (:stroke-width attrs)))
      (t/is (= "dotted" (:stroke-style attrs)) "penpot-extra-attrs survive birth")
      (t/is (not (contains? attrs :data-foo)) "unknown attrs are dropped")
      (t/is (not (contains? attrs :fillRule)) "no camel keys survive"))))

(t/deftest birth-stores-def-node-attrs-as-kebab
  (let [svg-data {:name "defs"
                  :tag :svg
                  :attrs {:width "100" :height "100" :viewBox "0 0 100 100"}
                  :content [{:tag :linearGradient
                             :attrs {:id "MyGrad"
                                     :gradientUnits "userSpaceOnUse"
                                     :gradientTransform "rotate(45)"
                                     :xlink:href "#missing"
                                     :data-foo "drop-me"}
                             :content [{:tag :stop
                                        :attrs {:offset "0"
                                                :stop-color "#ffffff"
                                                :style "stop-opacity:0.5"}
                                        :content []}]}
                            {:tag :filter
                             :attrs {:id "blur1" :filterUnits "userSpaceOnUse"}
                             :content [{:tag :feGaussianBlur
                                        :attrs {:stdDeviation "5"}
                                        :content []}]}
                            {:tag :rect
                             :attrs {:x "10" :y "10" :width "80" :height "80"
                                     :fill "url(#MyGrad)"}
                             :content []}]}
        [root _] (sb/create-svg-shapes svg-data {:x 0 :y 0} {} nil nil #{} false)
        defs     (:svg-defs root)
        grad     (get defs "MyGrad")
        blur     (get defs "blur1")]
    (t/is (contains? defs "MyGrad") "outer def id strings are byte-identical")
    (t/is (= :linearGradient (:tag grad)) "tags are untouched")
    (t/is (= "MyGrad" (get-in grad [:attrs :id])) ":id survives")
    (t/is (= "userSpaceOnUse" (get-in grad [:attrs :gradient-units])))
    (t/is (= "rotate(45)" (get-in grad [:attrs :gradient-transform])))
    (t/is (= "#missing" (get-in grad [:attrs :xlink-href])))
    (t/is (not (contains? (:attrs grad) :data-foo)) "unknown def attrs are dropped")
    (t/is (not (contains? (:attrs grad) :gradientUnits)) "no camel keys survive")
    (t/is (= "0.5" (get-in grad [:content 0 :attrs :stop-opacity])) "nested stops are normalized")
    (t/is (= "#ffffff" (get-in grad [:content 0 :attrs :stop-color])))
    (t/is (= "userSpaceOnUse" (get-in blur [:attrs :filter-units])))
    (t/is (= "5" (get-in blur [:content 0 :attrs :std-deviation])))))

(t/deftest birth-kebabizes-content-attrs-without-filtering
  (let [svg-data {:name "raw"
                  :tag :svg
                  :attrs {:width "100" :height "100" :viewBox "0 0 100 100"}
                  :content [{:tag :text
                             :attrs {:x "10" :y "20"
                                     :stroke-style "dotted"
                                     :strokeWidth "3"
                                     :data-foo "keep-me"}
                             :content ["hello"]}]}
        [_ children] (sb/create-svg-shapes svg-data {:x 0 :y 0} {} nil nil #{} false)
        raw          (first (filter #(= :svg-raw (:type %)) children))]
    (t/is (some? raw))
    (let [content (:content raw)]
      (t/is (= :text (:tag content)) "tags are untouched")
      (t/is (= "dotted" (get-in content [:attrs :stroke-style])) "the sidebar reads this key")
      (t/is (= "3" (get-in content [:attrs :stroke-width])) "camel is kebab-ized")
      (t/is (= "keep-me" (get-in content [:attrs :data-foo])) "unknown content keys survive")
      (t/is (= ["hello"] (:content content)) "values are untouched"))))

(t/deftest birth-setup-fill-reads-kebab-attrs
  (let [svg-data {:name "fill"
                  :tag :svg
                  :attrs {:width "100" :height "100" :viewBox "0 0 100 100"}
                  :content [{:tag :rect
                             :attrs {:x "10" :y "10" :width "80" :height "80"
                                     :fill "#ff0000"
                                     :fill-opacity "0.5"}
                             :content []}
                            {:tag :rect
                             :attrs {:x "10" :y "50" :width "80" :height "40"
                                     :style "fill:#00ff00;fill-opacity:0.25"}
                             :content []}]}
        [_ children] (sb/create-svg-shapes svg-data {:x 0 :y 0} {} nil nil #{} false)
        ;; NOTE: `create-svg-shapes` prepends a hidden background rect, skip it
        [r1 r2]      (filter #(and (= :rect (:type %)) (not (:hidden %))) children)
        f1           (first (:fills r1))
        f2           (first (:fills r2))]
    (t/is (some? (:fill-color f1)))
    (t/is (= 0.5 (:fill-opacity f1)))
    (t/is (nil? (get-in r1 [:svg-attrs :fill])) "consumed keys are removed")
    (t/is (nil? (get-in r1 [:svg-attrs :fill-opacity])))
    (t/is (some? (:fill-color f2)) "style-form fills work too")
    (t/is (= 0.25 (:fill-opacity f2)))
    (t/is (nil? (get-in r2 [:svg-attrs :style :fill])))
    (t/is (nil? (get-in r2 [:svg-attrs :style :fill-opacity])))))

(t/deftest birth-setup-stroke-reads-kebab-attrs
  (let [svg-data {:name "stroke"
                  :tag :svg
                  :attrs {:width "100" :height "100" :viewBox "0 0 100 100"}
                  :content [{:tag :path
                             :attrs {:d "M0 0L10 10"
                                     :fill "none"
                                     :stroke "#ff0000"
                                     :stroke-width "3"
                                     :stroke-opacity "0.5"
                                     :stroke-linecap "round"}
                             :content []}]}
        [_ children] (sb/create-svg-shapes svg-data {:x 0 :y 0} {} nil nil #{} false)
        path         (first (filter #(= :path (:type %)) children))
        stroke       (first (:strokes path))]
    (t/is (some? path))
    (t/is (some? (:stroke-color stroke)))
    (t/is (= 3.0 (:stroke-width stroke)))
    (t/is (= 0.5 (:stroke-opacity stroke)))
    (t/is (= :round (:stroke-cap-start stroke)))
    (t/is (= :round (:stroke-cap-end stroke)))
    (t/is (= "round" (get-in path [:svg-attrs :stroke-linecap])) "linecap stays for renderers")
    (t/is (nil? (get-in path [:svg-attrs :stroke-width])) "consumed keys are removed")
    (t/is (nil? (get-in path [:svg-attrs :stroke-opacity])))))

(t/deftest birth-setup-opacity-reads-kebab-blend-mode
  ;; Regression for PR #12006 review: `setup-opacity` still looked up
  ;; `:mixBlendMode` (camel) after birth started storing kebab keys, so
  ;; `mix-blend-mode` survived in `:svg-attrs` and `:blend-mode` was
  ;; never set. Birth stores the style map kebab-ized.
  (let [svg-data {:name "blend"
                  :tag :svg
                  :attrs {:width "100" :height "100" :viewBox "0 0 100 100"}
                  :content [{:tag :rect
                             :attrs {:x "10" :y "10" :width "80" :height "80"
                                     :style "mix-blend-mode: multiply"}
                             :content []}]}
        [_ children] (sb/create-svg-shapes svg-data {:x 0 :y 0} {} nil nil #{} false)
        rect         (first (filter #(and (= :rect (:type %)) (not (:hidden %))) children))]
    (t/is (some? rect))
    (t/is (= :multiply (:blend-mode rect)))
    (t/is (nil? (get-in rect [:svg-attrs :style :mix-blend-mode])) "consumed keys are removed")))

(t/deftest birth-child-style-beats-inherited-group-attr
  ;; Regression: `inherit-attributes` filtered on `(:styles attrs)`
  ;; (plural, always nil) instead of `(:style attrs)`, so a group attr
  ;; leaked next to the child's own style and `setup-stroke` preferred
  ;; the group color. Per CSS the child's inline style wins.
  (let [svg-data {:name "inherit"
                  :tag :svg
                  :attrs {:width "100" :height "100" :viewBox "0 0 100 100"}
                  :content [{:tag :g
                             :attrs {:stroke "#ff0000"}
                             :content [{:tag :rect
                                        :attrs {:x "10" :y "10" :width "80" :height "80"
                                                :fill "none"
                                                :style "stroke:#0000ff"}
                                        :content []}]}]}
        [_ children] (sb/create-svg-shapes svg-data {:x 0 :y 0} {} nil nil #{} false)
        rect         (first (filter #(and (= :rect (:type %)) (not (:hidden %))) children))
        stroke       (first (:strokes rect))]
    (t/is (some? rect))
    (t/is (= (clr/parse "#0000ff") (:stroke-color stroke)) "child style beats group attr")
    (t/is (nil? (get-in rect [:svg-attrs :stroke])) "group attr is not inherited over child style")))
