;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL


(ns frontend-tests.logic.path-lifecycle-test
  (:require
   [app.common.data.undo-stack :as u]
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.path :as path]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.edition :as dwe]
   [app.main.data.workspace.path.changes :as path.changes]
   [app.main.data.workspace.path.common :as path.common]
   [app.main.data.workspace.path.drawing :as path.drawing]
   [app.main.data.workspace.path.edition :as path.edition]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.data.workspace.path.selection :as path.selection]
   [app.main.data.workspace.path.shortcuts :as path.shortcuts]
   [app.main.data.workspace.path.state :as path.state]
   [app.main.data.workspace.path.streams :as path.streams]
   [app.main.data.workspace.path.tools :as path.tools]
   [app.main.data.workspace.path.undo :as path.undo]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.logic.path-test-helpers :as pth]
   [potok.v2.core :as ptk]))

(t/deftest path-lifecycle-selectors-use-the-active-path
  (let [id         (random-uuid)
        edit-state {:edit-mode :draw}
        edit-path  {id edit-state}
        state      {:workspace-local {:edition id
                                      :edit-path edit-path}
                    :workspace-drawing {:object {:id id :type :path}}}]
    (t/is (= edit-state (path.state/current-edit-state state)))
    (t/is (= edit-state (path.state/current-edit-state edit-path id)))
    (t/is (path.state/editing? state))
    (t/is (path.state/editing? edit-path id))
    (t/is (path.state/drawing? edit-state id :path {:id id :type :path}))))

(t/deftest path-drawing-selector-finds-new-paths
  (let [id    (random-uuid)
        state {:workspace-local {:edition nil
                                 :edit-path {id {}}}
               :workspace-drawing {:tool :path
                                   :object {:id id :type :path}}}]
    (t/is (path.state/drawing? state))
    (t/is (not (path.state/editing? state)))
    (t/is (path.state/drawing? nil nil :path {:id id :type :path}))
    (t/is (not (path.state/drawing? nil nil :curve {:id id :type :path})))))

(t/deftest clear-edition-mode-finishes-path-streams
  (t/is (path.streams/finish-edition? (dwe/clear-edition-mode)))
  (t/is (not (path.streams/finish-edition? :interrupt))))

(t/deftest clear-edition-mode-finishes-active-path-before-finalizing
  (let [id         (random-uuid)
        event      (dwe/clear-edition-mode)
        state      {:workspace-local {:edition id
                                      :edit-path {id {:edit-mode :move}}}
                    :workspace-drawing {:object {:id id}}}
        state'     (ptk/update event state)
        emissions  (atom [])]
    (->> (ptk/watch event state' nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (nil? (get-in state' [:workspace-local :edition])))
    (t/is (some? (get-in state' [:workspace-local :edit-path id])))
    (t/is (= [::path.common/finish-path]
             (mapv ptk/type @emissions)))))

(t/deftest clear-non-path-edition-does-not-emit-finish-path
  (let [id        (random-uuid)
        event     (dwe/clear-edition-mode)
        state     {:workspace-local {:edition id}}
        state'    (ptk/update event state)
        emissions (atom [])]
    (->> (ptk/watch event state' nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (nil? (get-in state' [:workspace-local :edition])))
    (t/is (empty? @emissions))))

(t/deftest restarting-draw-mode-finishes-pending-subpath
  (let [file        (pth/setup-rect-file)
        shape       (-> (cths/get-shape file :rect1)
                        (path/convert-to-path))
        id          (:id shape)
        last-point  (last (path/get-points (:content shape)))
        state       {:workspace-local
                     {:edition id
                      :edit-path
                      {id {:edit-mode :draw
                           :last-point last-point
                           :preview {:command :line-to
                                     :params {:x 150 :y 150}}
                           :old-content (:content shape)}}}
                     :workspace-drawing {:object shape}}
        stream      (rx/subject)
        emissions   (atom [])]
    (->> (ptk/watch (path.drawing/start-draw-mode*) state stream)
         (rx/take 4)
         (rx/subs! #(swap! emissions conj %)))
    (rx/push! stream (ptk/data-event ::path.drawing/end-edition
                                     {:restart? true}))
    (t/is (= [::path.drawing/start-edition
              ::path.common/finish-path
              ::path.drawing/check-changed-content
              ::path.drawing/start-draw-mode*]
             (mapv ptk/type @emissions)))
    (let [state'  (ptk/update (second @emissions) state)
          state'' (ptk/update (path.drawing/preview-next-point
                               {:x 200 :y 200})
                              state')]
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point])))
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :preview])))
      (t/is (= :move-to
               (get-in state'' [:workspace-local :edit-path id :preview :command]))))))

(t/deftest escape-does-not-restart-edited-path-draw-loop
  (let [id        (random-uuid)
        state     {:workspace-local
                   {:edition id
                    :edit-path {id {:edit-mode :draw}}}}
        stream    (rx/subject)
        emissions (atom [])]
    (t/is (path.drawing/restart-draw-loop? (path.common/finish-path)))
    (->> (ptk/watch (path.drawing/start-draw-mode*) state stream)
         (rx/subs! #(swap! emissions conj %)))
    (rx/push! stream (ptk/data-event ::path.drawing/end-edition
                                     {:restart? false}))
    (t/is (= [::path.drawing/start-edition]
             (mapv ptk/type @emissions)))))

(defn- run-handle-drawing-end
  "Runs the draw-ending flow and passes its events to `callback`."
  [restart? callback]
  (let [state     (pth/drawing-path-state)
        stream    (rx/subject)
        emissions (atom [])]
    (->> (ptk/watch (path.drawing/handle-drawing) state stream)
         (rx/subs! #(swap! emissions conj %)))
    (rx/push! stream (ptk/data-event ::path.drawing/end-edition
                                     {:restart? restart?}))
    ;; Wait for the asynchronous drawing-end event.
    (js/setTimeout
     (fn []
       (let [end-event     (last @emissions)
             end-emissions (atom [])]
         (->> (ptk/watch end-event state stream)
              (rx/subs! #(swap! end-emissions conj %)))
         (callback @end-emissions))))))

(t/deftest escape-ending-new-path-draw-does-not-reenter-edition
  (t/async
    done
    (run-handle-drawing-end
     false
     (fn [emissions]
       (t/is (= [::path.drawing/close-drawn-loops
                 ::path.drawing/setup-frame
                 ::dwdc/handle-finish-drawing
                 ::dwe/clear-edition-mode]
                (mapv ptk/type emissions)))
       (done)))))

(t/deftest finishing-new-path-draw-reenters-edition
  (t/async
    done
    (run-handle-drawing-end
     true
     (fn [emissions]
       (t/is (= [::path.common/finish-path
                 ::path.drawing/close-drawn-loops
                 ::path.drawing/setup-frame
                 ::dwdc/handle-finish-drawing
                 ::path.drawing/start-created-path-edition]
                (mapv ptk/type emissions)))
       (done)))))

(t/deftest escape-with-pending-segment-cancels-it-and-keeps-drawing
  (let [id        (random-uuid)
        state     {:workspace-local
                   {:edition id
                    :edit-path {id {:edit-mode :draw
                                    :last-point (gpt/point 10 10)
                                    :preview {:command :line-to
                                              :params {:x 20 :y 20}}}}}}
        emissions (atom [])]
    (->> (ptk/watch (path.shortcuts/esc-pressed) state nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (= [::path.common/cancel-pending-segment]
             (mapv ptk/type @emissions)))
    (let [state' (ptk/update (first @emissions) state)]
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point])))
      (t/is (nil? (get-in state' [:workspace-local :edit-path id :preview])))
      (t/is (= :draw (get-in state' [:workspace-local :edit-path id :edit-mode]))))))

(t/deftest escape-while-creating-path-finishes-it-into-edition
  (let [id        (random-uuid)
        state     {:workspace-local
                   {:edit-path {id {:edit-mode :draw
                                    :last-point (gpt/point 10 10)}}}
                   :workspace-drawing {:object {:id id :type :path}}}
        emissions (atom [])]
    (->> (ptk/watch (path.shortcuts/esc-pressed) state nil)
         (rx/subs! #(swap! emissions conj %)))
    ;; Finishing creates the shape and clears its pending segment.
    (t/is (= [::path.common/finish-path]
             (mapv ptk/type @emissions)))))

(t/deftest escape-without-pending-segment-interrupts-edition
  (let [id        (random-uuid)
        state     {:workspace-local
                   {:edition id
                    :edit-path {id {:edit-mode :draw}}}}
        emissions (atom [])]
    (->> (ptk/watch (path.shortcuts/esc-pressed) state nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (= [:interrupt] @emissions))))

(t/deftest editing-path-only-updates-drawing-copy
  (t/async
    done
    (let [file          (pth/setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          delta         (gpt/point 10 5)
          store         (ths/setup-store file)
          events        (conj (pth/start-path-edition-events id)
                              (pth/move-drawing-content delta))]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)
               drawing-copy (get-in new-state [:workspace-drawing :object])]
           (t/is (= original-rect stored-shape))
           (t/is (= :path (:type drawing-copy)))
           (t/is (= (path/move-content
                     (:content (path/convert-to-path original-rect))
                     delta)
                    (:content drawing-copy)))))))))

(t/deftest unchanged-path-edition-preserves-simple-shape
  (t/async
    done
    (let [file          (pth/setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          store         (ths/setup-store file)
          events        (conj (pth/start-path-edition-events id) :interrupt)]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= original-rect stored-shape))
           (t/is (= :rect (:type stored-shape)))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(t/deftest changed-path-edition-is-persisted-when-finalized
  (t/async
    done
    (let [file            (pth/setup-rect-file)
          original-rect   (cths/get-shape file :rect1)
          id              (:id original-rect)
          delta           (gpt/point 10 5)
          original-path   (path/convert-to-path original-rect)
          ;; Persist the rectangle with an explicit close command.
          changed-content (-> (:content original-path)
                              (path/move-content delta)
                              (path/close-loops))
          expected-shape  (-> original-path
                              (assoc :content changed-content)
                              (path/update-geometry))
          store           (ths/setup-store file)
          events          (into (pth/start-path-edition-events id)
                                [(pth/move-drawing-content delta)
                                 :interrupt])]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= expected-shape stored-shape))
           (t/is (= :path (:type stored-shape)))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(t/deftest created-path-edition-cleans-drawing-state-on-exit
  (t/async
    done
    (let [file          (pth/setup-rect-file)
          original-rect (cths/get-shape file :rect1)
          id            (:id original-rect)
          store         (ths/setup-store file)
          events        [(path.drawing/start-created-path-edition id)
                         :interrupt]]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [file'        (ths/get-file-from-state new-state)
               stored-shape (cths/get-shape file' :rect1)]
           (t/is (= original-rect stored-shape))
           (t/is (nil? (get-in new-state [:workspace-local :edition])))
           (t/is (nil? (get-in new-state [:workspace-local :edit-path id])))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(defn- with-dangling-subpath-start
  [content]
  (path/content (conj (vec content)
                      {:command :move-to
                       :params {:x 30 :y 40}})))

(t/deftest cancel-pending-segment-drops-dangling-subpath-start
  (let [id      (random-uuid)
        content (pth/selectable-path-content)
        state   {:workspace-local
                 {:edition id
                  :edit-path {id {:edit-mode :draw
                                  :last-point (gpt/point 30 40)
                                  :preview {:command :line-to
                                            :params {:x 50 :y 50}}}}}
                 :workspace-drawing
                 {:object {:id id
                           :type :path
                           :content (with-dangling-subpath-start content)}}}
        state'  (ptk/update (path.common/cancel-pending-segment) state)]
    (t/is (= (vec content)
             (vec (get-in state' [:workspace-drawing :object :content]))))
    (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point])))
    (t/is (nil? (get-in state' [:workspace-local :edit-path id :preview])))))

(t/deftest finish-path-drops-dangling-subpath-start
  (let [id      (random-uuid)
        content (pth/selectable-path-content)
        state   {:workspace-local
                 {:edition id
                  :edit-path {id {:edit-mode :draw
                                  :last-point (gpt/point 30 40)}}}
                 :workspace-drawing
                 {:object {:id id
                           :type :path
                           :content (with-dangling-subpath-start content)}}}
        state'  (ptk/update (path.common/finish-path) state)]
    (t/is (= (vec (path/close-subpaths content))
             (vec (get-in state' [:workspace-drawing :object :content]))))
    (t/is (nil? (get-in state' [:workspace-local :edit-path id :last-point])))))

(t/deftest finalize-ignores-dangling-subpath-start
  (let [file        (pth/setup-rect-file)
        rect        (cths/get-shape file :rect1)
        id          (:id rect)
        path-shape  (path/convert-to-path rect)
        old-content (:content path-shape)
        state       {:current-file-id (:id file)
                     :current-page-id (cthf/current-page-id file)
                     :files {(:id file) file}
                     :workspace-local
                     {:edition id
                      :edit-path {id {:old-content old-content}}}
                     :workspace-drawing
                     {:object (assoc path-shape
                                     :content
                                     (with-dangling-subpath-start old-content))}}
        emissions   (atom [])]
    (->> (ptk/watch (path.changes/finalize-path-content id) state nil)
         (rx/subs! #(swap! emissions conj %)))
    (t/is (empty? @emissions))))

(t/deftest next-point-preview-is-suppressed-during-a-modifier-drag
  (let [id      (random-uuid)
        content (path/content [{:command :move-to :params {:x 0 :y 0}}
                               {:command :line-to :params {:x 100 :y 0}}])
        mk      (fn [modifiers]
                  ;; Draw mode keeps the path in the drawing object.
                  {:workspace-drawing {:object {:id id :type :path :content content}}
                   :workspace-local {:edition nil
                                     :zoom 1
                                     :edit-path {id {:edit-mode :draw
                                                     :last-point (gpt/point 100 0)
                                                     :content-modifiers modifiers}}}})
        event   (path.drawing/preview-next-point {:x 150 :y 40 :shift? false})
        idle    (ptk/update event (mk {}))
        during  (ptk/update event (mk {1 {:c1x 5 :c1y 5}}))]
    ;; no active drag: the next-point preview updates as usual
    (t/is (some? (get-in idle [:workspace-local :edit-path id :preview])))
    ;; a placed handler is being dragged mid-draw: the preview must not move
    (t/is (nil? (get-in during [:workspace-local :edit-path id :preview])))))

(t/deftest dragging-the-current-curve-forward-handle-while-drawing
  (let [id      (random-uuid)
        content (path/content [{:command :move-to :params {:x 0 :y 0}}
                               {:command :curve-to
                                :params {:x 100 :y 0 :c1x 30 :c1y 0 :c2x 70 :c2y 0}}])
        mk      (fn []
                  ;; Store the backward handle and transient forward handle.
                  {:workspace-drawing {:object {:id id :type :path :content content}}
                   :workspace-local {:edition nil
                                     :zoom 1
                                     :edit-path {id {:edit-mode :draw
                                                     :last-point (gpt/point 100 0)
                                                     :prev-handler (gpt/point 130 0)}}}})
        drag    (fn [alt?]
                  (ptk/update (path.drawing/drag-prev-handler
                               {:x 100 :y 50 :alt? alt? :shift? false})
                              (mk)))
        finish  (fn [state] (ptk/update (path.drawing/finish-drag) state))
        c2-of   (fn [state]
                  (-> (get-in state [:workspace-drawing :object :content])
                      (vec) (nth 1) :params (select-keys [:c2x :c2y])))]

    (t/testing "no alt: the forward handle follows the pointer and the committed backward handle mirrors it"
      (let [dragged (drag false)]
        ;; the forward handle tracks the pointer
        (t/is (= (gpt/point 100 50)
                 (get-in dragged [:workspace-local :edit-path id :drag-handler])))
        ;; the stale transient forward handle is cleared so it is not double-rendered
        (t/is (nil? (get-in dragged [:workspace-local :edit-path id :prev-handler])))
        (let [committed (finish dragged)]
          ;; c2 = 2*node - forward = (100,-50)
          (t/is (= {:c2x 100 :c2y -50} (c2-of committed)))
          ;; the new forward handle becomes the prev-handler
          (t/is (= (gpt/point 100 50)
                   (get-in committed [:workspace-local :edit-path id :prev-handler]))))))

    (t/testing "alt: the forward handle moves on its own, the backward handle stays put"
      (let [committed (finish (drag true))]
        (t/is (= {:c2x 70 :c2y 0} (c2-of committed)))))))

(t/deftest dragging-the-current-curve-backward-handle-while-drawing
  ;; Dragging the backward handle mirrors the transient forward handle.
  (let [id      (random-uuid)
        content (path/content [{:command :move-to :params {:x 0 :y 0}}
                               {:command :curve-to
                                :params {:x 100 :y 0 :c1x 30 :c1y 0 :c2x 70 :c2y 0}}])
        mk      (fn []
                  {:workspace-drawing {:object {:id id :type :path :content content}}
                   :workspace-local {:edition nil
                                     :zoom 1
                                     :edit-path {id {:edit-mode :draw
                                                     :last-point (gpt/point 100 0)
                                                     :prev-handler (gpt/point 130 0)}}}})
        ;; Drag the backward handle to `(70, -40)`.
        drag    (fn [mode]
                  (ptk/update (path.edition/modify-selected-handlers
                               id [1 :c2] {} 0 -40 mode (= mode :smart))
                              (mk)))
        prev-of (fn [state] (get-in state [:workspace-local :edit-path id :prev-handler]))]

    (t/testing "smart (no modifier): the forward handle mirrors the angle, keeping its own length"
      ;; Keep the forward handle's length while mirroring its angle.
      (t/is (= (gpt/point 118 24) (prev-of (drag :smart)))))

    (t/testing "mirror (mod): the forward handle full-mirrors to equal length"
      ;; Mirror the forward handle around the node.
      (t/is (= (gpt/point 130 40) (prev-of (drag :mirror)))))

    (t/testing "independent (alt): the forward handle is left untouched"
      (t/is (= (gpt/point 130 0) (prev-of (drag :independent)))))))

(t/deftest path-local-undo-redo-restores-content-and-clears-preview
  (let [id        (random-uuid)
        content-a (path/content [{:command :move-to :params {:x 0 :y 0}}
                                 {:command :line-to :params {:x 10 :y 0}}])
        content-b (path/content [{:command :move-to :params {:x 0 :y 0}}
                                 {:command :line-to :params {:x 10 :y 5}}])
        base      (-> (pth/selectable-path-state id content-a path.helpers/empty-selection)
                      (assoc-in [:workspace-local :edit-path id :undo-stack] (u/make-stack)))
        ;; Capture both content states around a stale preview.
        s1        (ptk/update (path.undo/add-undo-entry) base)
        s2        (-> (path.state/set-content s1 content-b)
                      (assoc-in [:workspace-local :edit-path id :preview]
                                {:command :line-to :params {:x 99 :y 99}}))
        s3        (ptk/update (path.undo/add-undo-entry) s2)
        s4        (ptk/update (path.undo/undo-path) s3)
        s5        (ptk/update (path.undo/redo-path) s4)]
    (t/is (= content-b (path.state/get-path s3 :content)))
    (t/is (= content-a (path.state/get-path s4 :content)))
    ;; Restoring an entry drops its render-only preview.
    (t/is (nil? (get-in s4 [:workspace-local :edit-path id :preview])))
    (t/is (= content-b (path.state/get-path s5 :content)))))

(t/deftest path-undo-entry-never-captures-the-transient-preview
  (let [id     (random-uuid)
        state  (-> (pth/selectable-path-state id (pth/selectable-path-content)
                                              path.helpers/empty-selection)
                   (assoc-in [:workspace-local :edit-path id :undo-stack] (u/make-stack))
                   (assoc-in [:workspace-local :edit-path id :preview]
                             {:command :line-to :params {:x 99 :y 99}}))
        state' (ptk/update (path.undo/add-undo-entry) state)
        entry  (u/peek (get-in state' [:workspace-local :edit-path id :undo-stack]))]
    (t/is (some? entry))
    (t/is (not (contains? entry :preview)))))

;; Tool operations through the full edition lifecycle.

(t/deftest tool-make-curve-persists-through-edition-lifecycle
  (t/async
    done
    (let [file   (pth/setup-rect-file)
          rect   (cths/get-shape file :rect1)
          id     (:id rect)
          store  (ths/setup-store file)
          events (into (pth/start-path-edition-events id)
                       [(path.selection/select-node 1 false)
                        (path.tools/make-curve)
                        :interrupt])]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [stored (cths/get-shape (ths/get-file-from-state new-state) :rect1)]
           (t/is (= :path (:type stored)))
           ;; make-curve on a corner introduces at least one curve segment
           (t/is (some #(= :curve-to (:command %)) (seq (:content stored))))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

(t/deftest tool-remove-node-persists-through-edition-lifecycle
  (t/async
    done
    (let [file       (pth/setup-rect-file)
          rect       (cths/get-shape file :rect1)
          id         (:id rect)
          orig-nodes (count (path/get-points (:content (path/convert-to-path rect))))
          store      (ths/setup-store file)
          events     (into (pth/start-path-edition-events id)
                           [(path.selection/select-node 1 false)
                            (path.tools/remove-node)
                            :interrupt])]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [stored (cths/get-shape (ths/get-file-from-state new-state) :rect1)]
           (t/is (= :path (:type stored)))
           ;; removing a node leaves fewer nodes than the converted rect had
           (t/is (< (count (path/get-points (:content stored))) orig-nodes))
           (t/is (nil? (get-in new-state [:workspace-drawing :object])))))))))

