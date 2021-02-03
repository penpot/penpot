(ns app.test-helpers.libraries
  (:require [cljs.test :as t :include-macros true]
            [cljs.pprint :refer [pprint]]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [app.common.uuid :as uuid]
            [app.common.geom.point :as gpt]
            [app.common.geom.shapes :as gsh]
            [app.common.pages :as cp]
            [app.common.pages.helpers :as cph]
            [app.main.data.workspace :as dw]
            [app.main.data.workspace.libraries-helpers :as dwlh]
            [app.test-helpers.pages :as thp]))

;; ---- Helpers to manage libraries and synchronization

(defn is-instance-root
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (some? (:component-id shape)))
  (t/is (= (:component-root? shape) true)))

(defn is-instance-subroot
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (some? (:component-id shape)))
  (t/is (nil? (:component-root? shape))))

(defn is-instance-head
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (some? (:component-id shape))))

(defn is-instance-child
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (nil? (:component-id shape)))
  (t/is (nil? (:component-file shape)))
  (t/is (nil? (:component-root? shape))))

(defn is-noninstance
  [shape]
  (t/is (nil? (:shape-ref shape)))
  (t/is (nil? (:component-id shape)))
  (t/is (nil? (:component-file shape)))
  (t/is (nil? (:component-root? shape)))
  (t/is (nil? (:remote-synced? shape)))
  (t/is (nil? (:touched shape))))

(defn is-from-file
  [shape file]
  (t/is (= (:component-file shape)
           (:id file))))

(defn resolve-instance-and-master
  [state root-inst-id]
  (let [page      (thp/current-page state)
        root-inst (cph/get-shape page root-inst-id)

        file      (dwlh/get-local-file state)
        component (cph/get-component
                    (:component-id root-inst)
                    (:id file)
                    file
                    nil)

        shapes-inst   (cph/get-object-with-children
                        root-inst-id
                        (:objects page))
        shapes-master (cph/get-object-with-children
                        (:shape-ref root-inst)
                        (:objects component))

        unique-refs (into #{} (map :shape-ref shapes-inst))

        master-exists? (fn [shape]
                         (t/is (some #(= (:id %) (:shape-ref shape))
                                     shapes-master)))]

    ;; Validate that the instance tree is well constructed
    (t/is (is-instance-root (first shapes-inst)))
    (run! is-instance-child (rest shapes-inst))
    (run! is-noninstance shapes-master)
    (t/is (= (count shapes-inst)
             (count shapes-master)
             (count unique-refs)))
    (run! master-exists? shapes-inst)

    [shapes-inst shapes-master component]))

(defn resolve-component
  [state component-id]
  (let [page      (thp/current-page state)

        file      (dwlh/get-local-file state)
        component (cph/get-component
                    component-id
                    (:id file)
                    file
                    nil)

        root-master   (cph/get-component-root
                        component)
        shapes-master (cph/get-object-with-children
                        (:id root-master)
                        (:objects component))]

    ;; Validate that the component tree is well constructed
    (run! is-noninstance shapes-master)

    [shapes-master component]))

