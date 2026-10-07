;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-texts-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.texts-events :as dwte]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.ui.shapes.text.styles :as text.styles]
   [app.main.ui.workspace.shapes.text.viewport-texts-html :as vth]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(defn- text-content
  [writing-mode]
  {:children [{:children [{:writing-mode writing-mode}]}]})

(t/deftest vertical-auto-height-grows-width
  (let [selrect   {:width 100 :height 200}
        dimension {:width 240 :height 360}
        content   (text-content "vertical-rl")]
    (t/is (= {:width 240 :height 200}
             (dwwt/resolve-text-size selrect :auto-height content dimension)))))

(t/deftest horizontal-auto-height-grows-height
  (let [selrect   {:width 100 :height 200}
        dimension {:width 240 :height 360}
        content   (text-content "horizontal-tb")]
    (t/is (= {:width 100 :height 360}
             (dwwt/resolve-text-size selrect :auto-height content dimension)))))

(t/deftest vertical-grow-type-resize-axes-are-remapped
  (t/is (= :auto-height
           (dwm/next-grow-type :auto-width (gpt/point 1 2) true)))
  (t/is (= :fixed
           (dwm/next-grow-type :auto-height (gpt/point 2 1) true)))
  (t/is (= :auto-height
           (dwm/next-grow-type :auto-height (gpt/point 1 2) true))))

(t/deftest vertical-export-styles-enable-inter-script-spacing
  (let [vertical   (text.styles/generate-paragraph-styles
                    nil
                    {:writing-mode "vertical-rl"
                     :text-orientation "upright"})
        horizontal (text.styles/generate-paragraph-styles
                    nil
                    {:writing-mode "horizontal-tb"})]
    (t/is (= "vertical-rl" (aget vertical "writingMode")))
    (t/is (= "upright" (aget vertical "textOrientation")))
    (t/is (= "normal" (aget vertical "textAutospace")))
    (t/is (nil? (aget horizontal "textAutospace")))))

(t/deftest text-export-styles-emit-font-features
  (let [palt (text.styles/generate-text-styles
              {:grow-type :fixed}
              {:font-features "palt"
               :font-size "20"
               :fills [{:fill-color "#000000" :fill-opacity 1}]})
        none (text.styles/generate-text-styles
              {:grow-type :fixed}
              {:font-features "none"
               :font-size "20"
               :fills [{:fill-color "#000000" :fill-opacity 1}]})]
    (t/is (= "\"palt\"" (aget palt "fontFeatureSettings")))
    (t/is (nil? (aget none "fontFeatureSettings")))))

(t/deftest text-export-styles-emit-annotation-clearance
  (let [style (text.styles/generate-text-styles
               {:grow-type :fixed}
               {:annotation-clearance "auto"
                :line-height "1.2"
                :ruby "かんじ"
                :text-emphasis "filled-dot"
                :font-size "20"
                :fills [{:fill-color "#000000" :fill-opacity 1}]})]
    (t/is (nil? (aget style "--annotation-clearance")))
    (t/is (= "calc(max(var(--paragraph-line-height, 1.2), 1.2) + 1)"
             (aget style "lineHeight")))))

(t/deftest annotation-clearance-adds-the-ruby-size-to-the-paragraph-line-height
  (let [style (text.styles/generate-text-styles
               {:grow-type :fixed}
               {:annotation-clearance "auto"
                :ruby "かんじ"
                :ruby-size "quarter"
                :font-size "20"
                :fills [{:fill-color "#000000" :fill-opacity 1}]})
        paragraph (text.styles/generate-paragraph-styles nil {:line-height "2"})]
    (t/is (= "calc(var(--paragraph-line-height, 1.2) + 0.25)" (aget style "lineHeight")))
    (t/is (= "2" (aget paragraph "--paragraph-line-height")))))

(t/deftest annotation-clearance-none-keeps-the-line-height
  (let [style (text.styles/generate-text-styles
               {:grow-type :fixed}
               {:annotation-clearance "none"
                :ruby "かんじ"
                :font-size "20"
                :fills [{:fill-color "#000000" :fill-opacity 1}]})]
    (t/is (nil? (aget style "lineHeight")))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-text-shape
  "Build a fully initialised text shape at the given position."
  [& {:keys [x y width height position-data]
      :or   {x 10 y 20 width 100 height 50}}]
  (cond-> (cts/setup-shape {:type   :text
                            :x      x
                            :y      y
                            :width  width
                            :height height})
    (some? position-data)
    (assoc :position-data position-data)))

(defn- make-degenerate-text-shape
  "Simulate a text shape decoded from the server via map->Rect (which bypasses
  make-rect's 0.01 minimum enforcement), giving it a zero-width / zero-height
  selrect.  This is the exact condition that triggered the original crash:
  change-dimensions-modifiers divided by sr-width (== 0), producing an Infinity
  scale factor that propagated through the transform pipeline until
  calculate-selrect / center->rect returned nil, and then gpt/point threw
  'invalid arguments (on pointer constructor)'."
  [& {:keys [x y width height]
      :or   {x 10 y 20 width 0 height 0}}]
  (-> (make-text-shape :x x :y y :width 100 :height 50)
      ;; Bypass make-rect by constructing the Rect record directly, the same
      ;; way decode-rect does during JSON deserialization from the backend.
      (assoc :selrect (grc/map->Rect {:x x :y y
                                      :width  width :height  height
                                      :x1 x :y1 y
                                      :x2 (+ x width) :y2 (+ y height)}))))

(defn- sample-position-data
  "Return a minimal position-data vector with the supplied coords."
  [x y]
  [{:x x :y y :width 80 :height 16 :fills [] :text "hello"}])

;; ---------------------------------------------------------------------------
;; Tests: nil / no-op guard
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-nil-modifier-returns-shape-unchanged
  (t/testing "nil text-modifier returns the original shape untouched"
    (let [shape  (make-text-shape)
          result (dwt/apply-text-modifier shape nil)]
      (t/is (= shape result)))))

(t/deftest apply-text-modifier-empty-map-no-keys-returns-shape-unchanged
  (t/testing "modifier with no recognised keys leaves shape unchanged"
    (let [shape    (make-text-shape)
          modifier {}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= (:selrect shape) (:selrect result)))
      (t/is (= (:width result) (:width shape)))
      (t/is (= (:height result) (:height shape))))))

;; ---------------------------------------------------------------------------
;; Tests: width modifier
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-width-changes-shape-width
  (t/testing "width modifier resizes the shape width"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width 200}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 200.0 (-> result :selrect :width))))))

(t/deftest apply-text-modifier-width-nil-skips-width-change
  (t/testing "nil :width in modifier does not alter the width"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width nil}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= (-> shape :selrect :width) (-> result :selrect :width))))))

;; ---------------------------------------------------------------------------
;; Tests: height modifier
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-height-changes-shape-height
  (t/testing "height modifier resizes the shape height"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:height 120}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 120.0 (-> result :selrect :height))))))

(t/deftest apply-text-modifier-height-nil-skips-height-change
  (t/testing "nil :height in modifier does not alter the height"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:height nil}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= (-> shape :selrect :height) (-> result :selrect :height))))))

;; ---------------------------------------------------------------------------
;; Tests: width + height together
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-width-and-height-both-applied
  (t/testing "both width and height are applied simultaneously"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width 300 :height 80}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 300.0 (-> result :selrect :width)))
      (t/is (= 80.0  (-> result :selrect :height))))))

;; ---------------------------------------------------------------------------
;; Tests: position-data modifier
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-position-data-is-set-on-shape
  (t/testing "position-data modifier replaces the position-data on shape"
    (let [pd       (sample-position-data 5 10)
          shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:position-data pd}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? (:position-data result))))))

(t/deftest apply-text-modifier-position-data-nil-leaves-position-data-unchanged
  (t/testing "nil :position-data in modifier does not alter position-data"
    (let [pd       (sample-position-data 5 10)
          shape    (-> (make-text-shape :x 0 :y 0 :width 100 :height 50)
                       (assoc :position-data pd))
          modifier {:position-data nil}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= pd (:position-data result))))))

;; ---------------------------------------------------------------------------
;; Tests: position-data is translated by delta when shape moves
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-position-data-translated-on-resize
  (t/testing "position-data x/y is adjusted by the delta of the selrect origin"
    (let [pd       (sample-position-data 10 20)
          shape    (-> (make-text-shape :x 0 :y 0 :width 100 :height 50)
                       (assoc :position-data pd))
          ;; Only set position-data; no resize so no origin shift expected
          modifier {:position-data pd}
          result   (dwt/apply-text-modifier shape modifier)]
      ;; Delta should be zero (no dimension change), so coords stay the same
      (t/is (= 10.0 (-> result :position-data first :x)))
      (t/is (= 20.0 (-> result :position-data first :y))))))

(t/deftest apply-text-modifier-position-data-not-translated-when-nil
  (t/testing "nil position-data on result after modifier is left as nil"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width 200}
          result   (dwt/apply-text-modifier shape modifier)]
      ;; shape had no position-data; modifier doesn't set one — stays nil
      (t/is (nil? (:position-data result))))))

;; ---------------------------------------------------------------------------
;; Tests: degenerate selrect (zero width or height decoded from the server)
;;
;; Root cause of the original crash:
;;   change-dimensions-modifiers divided by (:width selrect) or (:height selrect)
;;   which is 0 when the shape was decoded via map->Rect (bypassing make-rect's
;;   0.01 minimum), producing Infinity → transform pipeline returned nil selrect
;;   → gpt/point threw "invalid arguments (on pointer constructor)".
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-zero-width-selrect-does-not-throw
  (t/testing "width modifier on a shape with zero selrect width does not throw"
    ;; Simulates a shape received from the server whose selrect has width=0
    ;; (map->Rect bypasses the 0.01 floor of make-rect).
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 50)
          modifier {:width 200}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

(t/deftest apply-text-modifier-zero-height-selrect-does-not-throw
  (t/testing "height modifier on a shape with zero selrect height does not throw"
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 100 :height 0)
          modifier {:height 80}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

(t/deftest apply-text-modifier-zero-width-and-height-selrect-does-not-throw
  (t/testing "both modifiers on a fully-degenerate selrect do not throw"
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 0)
          modifier {:width 150 :height 60}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

(t/deftest apply-text-modifier-zero-width-selrect-result-has-correct-width
  (t/testing "applying width modifier to a zero-width shape yields the requested width"
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 50)
          modifier {:width 200}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 200.0 (-> result :selrect :width))))))

(t/deftest apply-text-modifier-zero-height-selrect-result-has-correct-height
  (t/testing "applying height modifier to a zero-height shape yields the requested height"
    (let [shape    (make-degenerate-text-shape :x 0 :y 0 :width 100 :height 0)
          modifier {:height 80}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= 80.0 (-> result :selrect :height))))))

(t/deftest apply-text-modifier-nil-modifier-on-degenerate-shape-returns-unchanged
  (t/testing "nil modifier on a zero-selrect shape returns the same shape"
    (let [shape  (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 0)
          result (dwt/apply-text-modifier shape nil)]
      (t/is (identical? shape result)))))

;; ---------------------------------------------------------------------------
;; Tests: shape origin is preserved when there is no dimension change
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-selrect-origin-preserved-without-resize
  (t/testing "selrect x/y origin does not shift when no dimension changes"
    (let [shape    (make-text-shape :x 30 :y 40 :width 100 :height 50)
          modifier {:position-data (sample-position-data 30 40)}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (= (-> shape :selrect :x) (-> result :selrect :x)))
      (t/is (= (-> shape :selrect :y) (-> result :selrect :y))))))

;; ---------------------------------------------------------------------------
;; Tests: returned shape is a proper map-like value
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-returns-shape-with-required-keys
  (t/testing "result always contains the core shape keys"
    (let [shape    (make-text-shape :x 0 :y 0 :width 100 :height 50)
          modifier {:width 200 :height 80}
          result   (dwt/apply-text-modifier shape modifier)]
      (t/is (some? (:id result)))
      (t/is (some? (:type result)))
      (t/is (some? (:selrect result))))))

(t/deftest apply-text-modifier-nil-modifier-returns-same-identity
  (t/testing "nil modifier returns the exact same shape object (identity)"
    (let [shape (make-text-shape)]
      (t/is (identical? shape (dwt/apply-text-modifier shape nil))))))

;; ---------------------------------------------------------------------------
;; Tests: delta-move computation does not throw on degenerate selrect
;;
;; The delta-move in apply-text-modifier calls gpt/point on both the
;; original and new shape selrects.  gpt/point throws when given a
;; non-point-like value (nil, or a map with non-finite :x/:y).  Using
;; ctm/safe-size-rect instead of raw (:selrect …) access ensures a valid
;; rect is always available for that computation.
;; ---------------------------------------------------------------------------

(t/deftest apply-text-modifier-position-data-with-degenerate-selrect-does-not-throw
  (t/testing "position-data modifier on a zero-selrect shape does not throw"
    (let [pd     (sample-position-data 5 10)
          shape  (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 0)
          result (dwt/apply-text-modifier shape {:position-data pd})]
      (t/is (some? result))
      (t/is (= pd (:position-data result)))))

  (t/testing "width + position-data modifier on a zero-selrect shape does not throw"
    (let [pd     (sample-position-data 5 10)
          shape  (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 0)
          result (dwt/apply-text-modifier shape {:width 200 :position-data pd})]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

;; ---------------------------------------------------------------------------
;; Tests: add-typography event normalises float line-height / letter-spacing
;;
;; These tests exercise the full add-typography event path in texts.cljs:
;; a text shape whose content nodes carry float line-height / letter-spacing
;; values (as produced by JS float arithmetic) must yield a typography entry
;; in the file with properly-rounded *string* values, not the raw floats.
;;
;; Root cause reproduced here: JS float arithmetic (e.g. 1.3 - 0.1) can
;; produce 1.2000000000000002 instead of 1.2.  Without the fix, that number
;; survives through add-typography unchanged, and (ctt/check-typography)
;; would throw because :line-height must be a :string.
;; With the fix (mth/precision + str), it is normalised to "1.2" before the
;; schema check runs.
;; ---------------------------------------------------------------------------

(t/deftest add-typography-normalises-float-line-height
  (t/async
    done
    (let [;; Exact value reproduced from the issue: 1.3 - 0.1 in JS
          float-lh  1.2000000000000002
          content   (txt/change-text nil "hello" :line-height float-lh)
          file      (-> (cthf/sample-file :file1)
                        (cths/add-sample-shape :text1
                                               :type    :text
                                               :x 0 :y 0
                                               :content content))
          shape-id  (:id (cths/get-shape file :text1))
          file-id   (:id file)
          store     (ths/setup-store file)
          events    [;; Pre-select the text shape so add-typography can find it
                     (fn [state] (assoc-in state [:workspace-local :selected] #{shape-id}))
                     (dwte/add-typography file-id)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               typographies (vals (get-in file' [:data :typographies]))]
           (t/is (= 1 (count typographies))
                 "exactly one typography was added")
           (t/is (= "1.2" (:line-height (first typographies)))
                 "float line-height is normalised to 2-decimal string")))))))

(t/deftest add-typography-truncates-line-height-to-two-decimals
  (t/async
    done
    (let [;; A value with more than 2 decimal places: 1.234234234 → "1.23"
          long-lh   1.234234234
          content   (txt/change-text nil "hello" :line-height long-lh)
          file      (-> (cthf/sample-file :file1)
                        (cths/add-sample-shape :text1
                                               :type    :text
                                               :x 0 :y 0
                                               :content content))
          shape-id  (:id (cths/get-shape file :text1))
          file-id   (:id file)
          store     (ths/setup-store file)
          events    [(fn [state] (assoc-in state [:workspace-local :selected] #{shape-id}))
                     (dwte/add-typography file-id)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               typographies (vals (get-in file' [:data :typographies]))]
           (t/is (= 1 (count typographies))
                 "exactly one typography was added")
           (t/is (= "1.23" (:line-height (first typographies)))
                 "line-height with more than 2 decimals is truncated to 2")))))))

(t/deftest add-typography-normalises-float-letter-spacing
  (t/async
    done
    (let [;; Analogous imprecision for letter-spacing
          float-ls  0.10000000000000001
          content   (txt/change-text nil "hello" :letter-spacing float-ls)
          file      (-> (cthf/sample-file :file1)
                        (cths/add-sample-shape :text1
                                               :type    :text
                                               :x 0 :y 0
                                               :content content))
          shape-id  (:id (cths/get-shape file :text1))
          file-id   (:id file)
          store     (ths/setup-store file)
          events    [(fn [state] (assoc-in state [:workspace-local :selected] #{shape-id}))
                     (dwte/add-typography file-id)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               typographies (vals (get-in file' [:data :typographies]))]
           (t/is (= 1 (count typographies))
                 "exactly one typography was added")
           (t/is (= "0.1" (:letter-spacing (first typographies)))
                 "float letter-spacing is normalised to 2-decimal string")))))))

;; ---------------------------------------------------------------------------
;; Tests: add-typography must not create an asset from a deleted font
;;
;; A text shape can keep referencing a font-id that is no longer installed
;; (the font was removed, or belongs to an unavailable library). Creating a
;; typography from it would bake that broken font-id into a brand-new asset.
;; ---------------------------------------------------------------------------

(t/deftest add-typography-skips-shape-with-deleted-font
  (t/async
    done
    (let [content   (txt/change-text nil "hello" :font-id "deleted-font-id")
          file      (-> (cthf/sample-file :file1)
                        (cths/add-sample-shape :text1
                                               :type    :text
                                               :x 0 :y 0
                                               :content content))
          shape-id  (:id (cths/get-shape file :text1))
          file-id   (:id file)
          store     (ths/setup-store file)
          events    [(fn [state] (assoc-in state [:workspace-local :selected] #{shape-id}))
                     (dwte/add-typography file-id)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               typographies (vals (get-in file' [:data :typographies]))
               shape'       (cths/get-shape file' :text1)]
           (t/is (= 0 (count typographies))
                 "no typography was added for a shape with a deleted font")
           (t/is (nil? (:typography-ref-id shape'))
                 "the shape was not linked to a typography")))))))

;; ---------------------------------------------------------------------------
;; Tests: add-typography must not create an asset from two or more selected texts
;;
;; Deriving a typography from a specific text only makes sense for a single
;; shape; with several texts selected there is no one style to capture.
;; ---------------------------------------------------------------------------

(t/deftest add-typography-skips-multiple-selected-texts
  (t/async
    done
    (let [file      (-> (cthf/sample-file :file1)
                        (cths/add-sample-shape :text1
                                               :type    :text
                                               :x 0 :y 0
                                               :content (txt/change-text nil "hello"))
                        (cths/add-sample-shape :text2
                                               :type    :text
                                               :x 0 :y 100
                                               :content (txt/change-text nil "world")))
          shape-id-1 (:id (cths/get-shape file :text1))
          shape-id-2 (:id (cths/get-shape file :text2))
          file-id   (:id file)
          store     (ths/setup-store file)
          events    [(fn [state] (assoc-in state [:workspace-local :selected] #{shape-id-1 shape-id-2}))
                     (dwte/add-typography file-id)]]

      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               typographies (vals (get-in file' [:data :typographies]))]
           (t/is (= 0 (count typographies))
                 "no typography was added when two texts are selected")))))))

;; ---------------------------------------------------------------------------
;; Tests: save-default-font must not persist typography refs into the global default font
;;
;; Root cause of #10925: typography assets are file-specific references, but
;; save-default-font used to write :typography-ref-id / :typography-ref-file into the
;; session-global [:workspace-global :default-font]. That state survives a file
;; switch, and v2-default-text-content bakes it into brand-new text shapes in
;; the other file, so they got a non-existent typography asset instead of the
;; default Penpot font. save-default-font now strips those two keys.
;; ---------------------------------------------------------------------------

(t/deftest save-font-strips-typography-refs-from-default-font
  (t/async
    done
    (let [file  (-> (cthf/sample-file :file1)
                    (cths/add-sample-shape :text1
                                           :type    :text
                                           :x 0 :y 0
                                           :content (txt/change-text nil "hello")))
          store (ths/setup-store file)
          attrs {:font-id "roboto"
                 :font-family "Roboto"
                 :font-variant-id "regular"
                 :font-size "14"
                 :typography-ref-id (uuid/next)
                 :typography-ref-file (:id file)}]
      (ths/run-store
       store done [(dwt/save-default-font attrs)]
       (fn [new-state]
         (let [default-font (get-in new-state [:workspace-global :default-font])]
           (t/is (some? default-font))
           (t/is (= "roboto" (:font-id default-font)))
           (t/is (nil? (:typography-ref-id default-font)))
           (t/is (nil? (:typography-ref-file default-font)))))))))

(t/deftest save-font-preserves-other-font-attrs
  (t/async
    done
    (let [store (ths/setup-store (cthf/sample-file :file1))
          attrs {:font-family "Open Sans"
                 :font-id "opensans"
                 :font-variant-id "regular"
                 :font-size "18"
                 :line-height "1.5"
                 :letter-spacing "0"
                 :typography-ref-id (uuid/next)
                 :typography-ref-file (uuid/next)}]
      (ths/run-store store done [(dwt/save-default-font attrs)]
                     (fn [new-state]
                       (let [default-font (get-in new-state [:workspace-global :default-font])]
                         (t/is (= "Open Sans" (:font-family default-font)))
                         (t/is (= "18" (:font-size default-font)))
                         (t/is (= "1.5" (:line-height default-font)))
                         (t/is (nil? (:typography-ref-id default-font)))
                         (t/is (nil? (:typography-ref-file default-font)))))))))

;; ---------------------------------------------------------------------------
;; Tests: fix-position with degenerate selrect
;; ---------------------------------------------------------------------------

(t/deftest fix-position-zero-width-selrect-does-not-throw
  (t/testing "fix-position on a shape with zero selrect width does not throw"
    (let [shape     (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 50)
          modifiers (ctm/change-dimensions-modifiers shape :width 200 {:ignore-lock? true})
          shape'    (assoc shape :modifiers modifiers)
          result    (vth/fix-position shape')]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

(t/deftest fix-position-zero-height-selrect-does-not-throw
  (t/testing "fix-position on a shape with zero selrect height does not throw"
    (let [shape     (make-degenerate-text-shape :x 0 :y 0 :width 100 :height 0)
          modifiers (ctm/change-dimensions-modifiers shape :height 80 {:ignore-lock? true})
          shape'    (assoc shape :modifiers modifiers)
          result    (vth/fix-position shape')]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

(t/deftest fix-position-zero-width-and-height-selrect-does-not-throw
  (t/testing "fix-position on a fully degenerate selrect does not throw"
    (let [shape     (make-degenerate-text-shape :x 0 :y 0 :width 0 :height 0)
          modifiers (ctm/change-dimensions-modifiers shape :width 150 {:ignore-lock? true})
          shape'    (assoc shape :modifiers modifiers)
          result    (vth/fix-position shape')]
      (t/is (some? result))
      (t/is (some? (:selrect result))))))

;; ---------------------------------------------------------------------------
;; Tests: ensure-valid-text-content
;; ---------------------------------------------------------------------------

(t/deftest ensure-valid-text-content-empty-children-repaired
  (t/testing "root with empty :children vector is repaired to canonical tree"
    (let [broken  {:type         "root"
                   :vertical-align "top"
                   :children     []}
          fixed   (dwt/ensure-valid-text-content broken)]
      (t/is (= "root" (:type fixed)))
      (t/is (vector? (:children fixed)))
      (t/is (= 1 (count (:children fixed)))
            "exactly one paragraph-set is seeded")
      (t/is (= "paragraph-set" (get-in fixed [:children 0 :type])))
      (t/is (pos? (count (get-in fixed [:children 0 :children])))
            "paragraph-set has at least one paragraph")
      (t/is (= "" (get-in fixed [:children 0 :children 0 :children 0 :text]))
            "seeded span has empty text")
      (t/is (= "top" (:vertical-align fixed))
            "preserves the original :vertical-align"))))

(t/deftest ensure-valid-text-content-missing-children-repaired
  (t/testing "root with no :children key is repaired to canonical tree"
    (let [broken  {:type "root" :vertical-align "center"}
          fixed   (dwt/ensure-valid-text-content broken)]
      (t/is (vector? (:children fixed)))
      (t/is (pos? (count (:children fixed))))
      (t/is (= "center" (:vertical-align fixed))))))

(t/deftest ensure-valid-text-content-healthy-tree-unchanged
  (t/testing "a well-formed content is returned unchanged"
    (let [healthy {:type "root"
                   :children [{:type "paragraph-set"
                               :children [{:type "paragraph"
                                           :children [{:text "hello"}]}]}]}
          fixed   (dwt/ensure-valid-text-content healthy)]
      (t/is (= healthy fixed)))))

(t/deftest ensure-valid-text-content-nil-unchanged
  (t/testing "nil content is returned unchanged (no repair)"
    (t/is (nil? (dwt/ensure-valid-text-content nil)))))

(t/deftest ensure-valid-text-content-non-root-unchanged
  (t/testing "a non-root content (e.g. paragraph) is left alone"
    (let [node {:type "paragraph" :children []}]
      (t/is (= node (dwt/ensure-valid-text-content node))))))

(defn- spans-content
  [& spans]
  {:type "root"
   :children [{:type "paragraph-set"
               :children [{:type "paragraph"
                           :children (vec spans)}]}]})

(defn- content-spans
  [shape]
  (txt/node-seq txt/is-text-node? (:content shape)))

(t/deftest range-style-over-part-of-a-ruby-span-styles-the-whole-span
  (let [shape  {:type :text
                :content (spans-content {:text "あ" :font-size "14"}
                                        {:text "漢字" :font-size "14" :ruby "かんじ"}
                                        {:text "い" :font-size "14"})}
        result (dwt/update-text-range-attrs shape 1 2 {:font-size "30"})]
    (t/is (= [["あ" "14" nil] ["漢字" "30" "かんじ"] ["い" "14" nil]]
             (mapv (juxt :text :font-size :ruby) (content-spans result))))))

(t/deftest range-style-over-part-of-a-warichu-span-styles-the-whole-span
  (let [shape  {:type :text
                :content (spans-content {:text "本文" :font-size "14"}
                                        {:text "割注" :font-size "14" :warichu "warichu"})}
        result (dwt/update-text-range-attrs shape 0 3 {:font-size "30"})]
    (t/is (= [["本文" "30" nil] ["割注" "30" "warichu"]]
             (mapv (juxt :text :font-size :warichu) (content-spans result))))))

(t/deftest single-span-range-is-false-across-span-boundaries
  (let [content (spans-content {:text "漢" :font-weight "700"}
                               {:text "字かな" :font-weight "400"})]
    (t/is (true? (dwt/single-span-range? content 1 3)))
    (t/is (false? (dwt/single-span-range? content 0 2)))))

(defn- paragraphs-content
  [& texts]
  {:type "root"
   :children [{:type "paragraph-set"
               :children (mapv (fn [text]
                                 {:type "paragraph"
                                  :children [{:text text :font-size "14"}]})
                               texts)}]})

(t/deftest range-style-in-a-later-paragraph-uses-document-offsets
  (let [shape  {:type :text :content (paragraphs-content "東京" "京都")}
        result (dwt/update-text-range-attrs shape 3 5 {:font-size "30"})]
    (t/is (= [["東京" "14"] ["京都" "30"]]
             (mapv (juxt :text :font-size) (content-spans result))))))

(t/deftest range-style-in-the-first-paragraph-leaves-later-paragraphs-alone
  (let [shape  {:type :text :content (paragraphs-content "東京" "京都")}
        result (dwt/update-text-range-attrs shape 0 2 {:font-size "30"})]
    (t/is (= [["東京" "30"] ["京都" "14"]]
             (mapv (juxt :text :font-size) (content-spans result))))))

(t/deftest single-span-range-counts-each-paragraph-at-its-own-offset
  (let [content (paragraphs-content "東京" "京都")]
    (t/is (true? (dwt/single-span-range? content 0 2)))
    (t/is (true? (dwt/single-span-range? content 3 5)))
    (t/is (false? (dwt/single-span-range? content 1 4)))))

(t/deftest ruby-presentation-of-several-texts-is-one-shape-update
  (let [file-id (uuid/next)
        page-id (uuid/next)
        ids     [(uuid/next) (uuid/next) (uuid/next)]
        objects (into {} (map (fn [id] [id {:id id :type :text}])) ids)
        state   {:current-file-id file-id
                 :current-page-id page-id
                 :features #{}
                 :files {file-id {:data {:pages-index {page-id {:objects objects}}}}}}
        events  (atom [])]
    (->> (ptk/watch (dwt/update-all-ruby-presentation ids {:ruby-size "third"}) state nil)
         (rx/subs! #(swap! events conj %)))
    (let [updates (filter #(= ::dwsh/update-shapes (ptk/type %)) @events)]
      (t/is (= 1 (count updates))))))
