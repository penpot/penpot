;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-path-edit-test
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path.state :as st]
   [app.main.data.workspace.selection :as dws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- base-state
  "Build a minimal workspace state with a single shape in the file objects
  map, selected (so `start-editing-selected` picks it up) and marked as the
  current path-edit shape (so `get-path-location` resolves to it for the
  `set-content` guard)."
  [shape]
  (let [page-id (uuid/uuid "00000000-0000-0000-0000-0000000000a1")
        file-id (uuid/uuid "00000000-0000-0000-0000-0000000000a2")]
    {:current-file-id file-id
     :current-page-id page-id
     :files {file-id {:data {:pages-index {page-id {:objects {(:id shape) shape}}}}}}
     :workspace-local {:selected #{(:id shape)}
                       :edition  (:id shape)}}))

;; ---------------------------------------------------------------------------
;; Task 1: single-shape path-edit entry converts non-path shapes first
;; ---------------------------------------------------------------------------

(t/deftest start-editing-image-emits-convert-before-path-edit
  (t/async done
    (let [shape {:id (uuid/uuid "00000000-0000-0000-0000-0000000000b1")
                 :type :image}
          state (base-state shape)
          event (dw/start-editing-selected)]
      (->> (ptk/watch event state nil)
           (rx/reduce conj [])
           (rx/subs!
            (fn [events]
              (let [types     (mapv ptk/type events)
                    convert   (d/index-of types :app.main.data.workspace.shapes/update-shapes)
                    path-edit (d/index-of types :app.main.data.workspace.path.edition/start-path-edit)]
                (t/is (some? convert)
                      "expected a convert-to-path update-shapes event")
                (t/is (some? path-edit)
                      "expected a start-path-edit event")
                (t/is (< convert path-edit)
                      "convert-to-path must run before start-path-edit")))
            (fn [cause]
              (done)
              (js/console.error "[error]:" cause)
              (t/do-report {:type :error :message "Stream error" :actual cause}))
            (fn [_]
              (done)))))))

(t/deftest start-editing-rect-emits-convert-before-path-edit
  (t/async done
    (let [shape {:id (uuid/uuid "00000000-0000-0000-0000-0000000000c1")
                 :type :rect}
          state (base-state shape)
          event (dw/start-editing-selected)]
      (->> (ptk/watch event state nil)
           (rx/reduce conj [])
           (rx/subs!
            (fn [events]
              (let [types     (mapv ptk/type events)
                    convert   (d/index-of types :app.main.data.workspace.shapes/update-shapes)
                    path-edit (d/index-of types :app.main.data.workspace.path.edition/start-path-edit)]
                (t/is (some? convert))
                (t/is (some? path-edit))
                (t/is (< convert path-edit))))
            (fn [cause]
              (done)
              (js/console.error "[error]:" cause)
              (t/do-report {:type :error :message "Stream error" :actual cause}))
            (fn [_]
              (done)))))))

(t/deftest start-editing-circle-emits-convert-before-path-edit
  (t/async done
    (let [shape {:id (uuid/uuid "00000000-0000-0000-0000-0000000000c2")
                 :type :circle}
          state (base-state shape)
          event (dw/start-editing-selected)]
      (->> (ptk/watch event state nil)
           (rx/reduce conj [])
           (rx/subs!
            (fn [events]
              (let [types     (mapv ptk/type events)
                    convert   (d/index-of types :app.main.data.workspace.shapes/update-shapes)
                    path-edit (d/index-of types :app.main.data.workspace.path.edition/start-path-edit)]
                (t/is (some? convert))
                (t/is (some? path-edit))
                (t/is (< convert path-edit))))
            (fn [cause]
              (done)
              (js/console.error "[error]:" cause)
              (t/do-report {:type :error :message "Stream error" :actual cause}))
            (fn [_]
              (done)))))))

(t/deftest start-editing-path-does-not-emit-convert
  (t/async done
    (let [shape {:id (uuid/uuid "00000000-0000-0000-0000-0000000000d1")
                 :type :path}
          state (base-state shape)
          event (dw/start-editing-selected)]
      (->> (ptk/watch event state nil)
           (rx/reduce conj [])
           (rx/subs!
            (fn [events]
              (let [types (mapv ptk/type events)]
                (t/is (nil? (d/index-of types :app.main.data.workspace.shapes/update-shapes))
                      "no convert-to-path should be emitted for an already-path shape")
                (t/is (some? (d/index-of types :app.main.data.workspace.path.edition/start-path-edit)))))
            (fn [cause]
              (done)
              (js/console.error "[error]:" cause)
              (t/do-report {:type :error :message "Stream error" :actual cause}))
            (fn [_]
              (done)))))))

;; ---------------------------------------------------------------------------
;; Store integration: verify actual state transformation after dispatch
;; ---------------------------------------------------------------------------

(t/deftest start-editing-image-converts-shape-via-store
  (t/async done
    (let [file     (cths/add-sample-shape (thf/sample-file "file") "img" :type :image)
          shape-id (-> (cths/get-shape file "img") :id)
          store    (ths/setup-store file)]
      (ths/run-store store done
                     [(dws/select-shapes (d/ordered-set shape-id))
                      (dw/start-editing-selected)]
                     (fn [new-state]
                       (let [file'  (ths/get-file-from-state new-state)
                             shape' (cths/get-shape-by-id file' shape-id)]
                         (t/is (= :path (:type shape'))
                               "shape must be converted to :path after start-editing")
                         (t/is (some? (:content shape'))
                               "converted shape must have non-nil :content")))))))

;; ---------------------------------------------------------------------------
;; Task 2: defensive assertion in st/set-content
;; ---------------------------------------------------------------------------

(t/deftest set-content-throws-on-non-path-shape
  (doseq [type [:image :rect :circle :text]]
    (t/testing (str "set-content throws when the editing shape is :" type)
      (let [shape {:id (uuid/uuid "00000000-0000-0000-0000-0000000000e1")
                   :type type}
            state (base-state shape)]
        (t/is (thrown? js/Error (st/set-content state nil)))))))

(t/deftest set-content-accepts-path-shape
  (t/testing "set-content does not throw on a :path shape"
    (let [shape {:id (uuid/uuid "00000000-0000-0000-0000-0000000000f1")
                 :type :path}
          state (base-state shape)]
      (t/is (some? (st/set-content state nil))))))

;; ---------------------------------------------------------------------------
;; End-to-end: image -> convert-to-path yields a valid :path with :content
;; (regression for the production :data-validation crash where a :mod-obj set
;;  :type :path without a :set :content op)
;; ---------------------------------------------------------------------------

(defn- convert-changes
  "Build the changes produced by `dwsh/update-shapes [id] path/convert-to-path`
  for the shape `id` in the given file."
  [file id]
  (let [objects (-> file :data :pages-index (get (thf/current-page-id file)) :objects)]
    (-> (pcb/empty-changes nil (thf/current-page-id file))
        (pcb/with-objects objects)
        (pcb/update-shapes [id] path/convert-to-path {:with-objects? true}))))

(t/deftest convert-image-to-path-emits-content-op
  (t/testing "converting an :image emits a :set :content op and yields a valid :path"
    (let [file     (cths/add-sample-shape (thf/sample-file "file") "img" :type :image)
          id       (-> (cths/get-shape file "img") :id)
          changes  (convert-changes file id)
          mod-obj  (->> (:redo-changes changes)
                        (filter #(= :mod-obj (:type %)))
                        (filter #(= id (:id %)))
                        first)
          ops      (:operations mod-obj)
          content-op (some #(and (= :set (:type %)) (= :content (:attr %)) %) ops)]
      (t/is (some? content-op)
            "convert-to-path must emit a :set :content op (the original bug omitted it)")
      (t/is (some? (:val content-op)) "the :content op must carry a non-nil value")
      (let [file' (thf/apply-changes file changes :validate? false)
            shape (cths/get-shape-by-id file' id)]
        (t/is (= :path (:type shape)))
        (t/is (some? (:content shape))
              "the converted shape must retain a non-nil :content")))))

(t/deftest re-convert-path-keeps-content
  (t/testing "re-converting an already :path shape keeps its :content (no corruption)"
    (let [file     (cths/add-sample-shape (thf/sample-file "file") "img" :type :image)
          id       (-> (cths/get-shape file "img") :id)
          file'    (thf/apply-changes file (convert-changes file id) :validate? false)
          changes2 (convert-changes file' id)
          file''   (thf/apply-changes file' changes2 :validate? false)
          shape    (cths/get-shape-by-id file'' id)]
      (t/is (= :path (:type shape)))
      (t/is (some? (:content shape))
            "a second convert must not drop :content from the :path shape"))))
