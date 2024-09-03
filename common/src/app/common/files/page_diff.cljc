;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.page-diff
  "Given a page in its old version and the new will retrieve a map with
  the differences that will have an impact in the snap data"
  (:require
   [app.common.data :as d]
   [clojure.set :as set]))

(defn calculate-page-diff
  [old-page page check-attrs]

  (let [old-objects (get old-page :objects)
        old-guides (or (get old-page :guides) [])

        new-objects (get page :objects)
        new-guides (or (get page :guides) [])

        changed-object?
        (fn [id]
          (let [oldv (get old-objects id)
                newv (get new-objects id)]
            ;; Check first without select-keys because is faster if they are
            ;; the same reference
            (and (not= oldv newv)
                 (not= (select-keys oldv check-attrs)
                       (select-keys newv check-attrs)))))

        frame?
        (fn [id]
          (or (= :frame (get-in new-objects [id :type]))
              (= :frame (get-in old-objects [id :type]))))

        changed-guide?
        (fn [id]
          (not= (get old-guides id)
                (get new-guides id)))

        deleted-object?
        #(and (contains? old-objects %)
              (not (contains? new-objects %)))

        deleted-guide?
        #(and (contains? old-guides %)
              (not (contains? new-guides %)))

        new-object?
        #(and (not (contains? old-objects %))
              (contains? new-objects %))

        new-guide?
        #(and (not (contains? old-guides %))
              (contains? new-guides %))

        changed-frame-object?
        #(and (contains? new-objects %)
              (contains? old-objects %)
              (not= (get-in old-objects [% :frame-id])
                    (get-in new-objects [% :frame-id])))

        changed-frame-guide?
        #(and (contains? new-guides %)
              (contains? old-guides %)
              (not= (get-in old-objects [% :frame-id])
                    (get-in new-objects [% :frame-id])))

        changed-attrs-object?
        #(and (contains? new-objects %)
              (contains? old-objects %)
              (= (get-in old-objects [% :frame-id])
                 (get-in new-objects [% :frame-id])))

        changed-attrs-guide?
        #(and (contains? new-guides %)
              (contains? old-guides %)
              (= (get-in old-objects [% :frame-id])
                 (get-in new-objects [% :frame-id])))

        changed-object-ids
        (into #{}
              (filter changed-object?)
              (set/union (set (keys old-objects))
                         (set (keys new-objects))))

        changed-guides-ids
        (into #{}
              (filter changed-guide?)
              (set/union (set (keys old-guides))
                         (set (keys new-guides))))

        get-diff-object (fn [id] [(get old-objects id) (get new-objects id)])
        get-diff-guide  (fn [id] [(get old-guides id) (get new-guides id)])

        ;; Shapes with different frame owner
        change-frame-shapes
        (->> changed-object-ids
             (into [] (comp (filter changed-frame-object?)
                            (map get-diff-object))))

        ;; Guides that changed frames
        change-frame-guides
        (->> changed-guides-ids
             (into [] (comp (filter changed-frame-guide?)
                            (map get-diff-guide))))

        removed-frames
        (->> changed-object-ids
             (into [] (comp (filter frame?)
                            (filter deleted-object?)
                            (map (d/getf old-objects)))))

        removed-shapes
        (->> changed-object-ids
             (into [] (comp (remove frame?)
                            (filter deleted-object?)
                            (map (d/getf old-objects)))))

        removed-guides
        (->> changed-guides-ids
             (into [] (comp (filter deleted-guide?)
                            (map (d/getf old-guides)))))

        updated-frames
        (->> changed-object-ids
             (into [] (comp (filter frame?)
                            (filter changed-attrs-object?)
                            (map get-diff-object))))

        updated-shapes
        (->> changed-object-ids
             (into [] (comp (remove frame?)
                            (filter changed-attrs-object?)
                            (map get-diff-object))))

        updated-guides
        (->> changed-guides-ids
             (into [] (comp (filter changed-attrs-guide?)
                            (map get-diff-guide))))

        new-frames
        (->> changed-object-ids
             (into [] (comp (filter frame?)
                            (filter new-object?)
                            (map (d/getf new-objects)))))

        new-shapes
        (->> changed-object-ids
             (into [] (comp (remove frame?)
                            (filter new-object?)
                            (map (d/getf new-objects)))))

        new-guides
        (->> changed-guides-ids
             (into [] (comp (filter new-guide?)
                            (map (d/getf new-guides)))))]
    {:change-frame-shapes change-frame-shapes
     :change-frame-guides change-frame-guides
     :removed-frames      removed-frames
     :removed-shapes      removed-shapes
     :removed-guides      removed-guides
     :updated-frames      updated-frames
     :updated-shapes      updated-shapes
     :updated-guides      updated-guides
     :new-frames          new-frames
     :new-shapes          new-shapes
     :new-guides          new-guides}))
