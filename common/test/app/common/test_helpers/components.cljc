(ns app.common.test-helpers.components
  (:require
   [clojure.test :as t]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]))

;; ---- Helpers to manage libraries and synchronization

(defn check-instance-root
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (some? (:component-id shape)))
  (t/is (= (:component-root? shape) true)))

(defn check-instance-subroot
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (some? (:component-id shape)))
  (t/is (nil? (:component-root? shape))))

(defn check-instance-child
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (nil? (:component-id shape)))
  (t/is (nil? (:component-file shape)))
  (t/is (nil? (:component-root? shape))))

(defn check-instance-inner
  [shape]
  (if (some? (:component-id shape))
    (check-instance-subroot shape)
    (check-instance-child shape)))

(defn check-noninstance
  [shape]
  (t/is (nil? (:shape-ref shape)))
  (t/is (nil? (:component-id shape)))
  (t/is (nil? (:component-file shape)))
  (t/is (nil? (:component-root? shape)))
  (t/is (nil? (:remote-synced? shape)))
  (t/is (nil? (:touched shape))))

(defn check-from-file
  [shape file]
  (t/is (= (:component-file shape)
           (:id file))))

(defn resolve-instance
  "Get the shape with the given id and all its children, and
   verify that they are a well constructed instance tree."
  [page root-inst-id]
  (let [root-inst   (ctn/get-shape page root-inst-id)
        shapes-inst (cph/get-children-with-self (:objects page)
                                                root-inst-id)]
    (check-instance-root (first shapes-inst))
    (run! check-instance-inner (rest shapes-inst))

    shapes-inst))

(defn resolve-noninstance
  "Get the shape with the given id and all its children, and
   verify that they are not a component instance."
  [page root-inst-id]
  (let [root-inst   (ctn/get-shape page root-inst-id)
        shapes-inst (cph/get-children-with-self (:objects page)
                                                root-inst-id)]
    (run! check-noninstance shapes-inst)

    shapes-inst))

(defn resolve-instance-and-main
  "Get the shape with the given id and all its children, and also
   the main component and all its shapes."
  [page root-inst-id libraries]
  (let [root-inst     (ctn/get-shape page root-inst-id)

        component     (ctf/get-component libraries (:component-id root-inst))

        shapes-inst   (cph/get-children-with-self (:objects page) root-inst-id)
        shapes-main   (cph/get-children-with-self (:objects component) (:shape-ref root-inst))

        unique-refs   (into #{} (map :shape-ref) shapes-inst)

        main-exists?  (fn [shape]
                        (let [component-shape
                              (ctn/get-component-shape (:objects page) shape)

                              component
                              (ctf/get-component libraries (:component-id component-shape))

                              main-shape
                              (ctn/get-shape component (:shape-ref shape))]

                        (t/is (some? main-shape))))]

    ;; Validate that the instance tree is well constructed
    (check-instance-root (first shapes-inst))
    (run! check-instance-inner (rest shapes-inst))
    (t/is (= (count shapes-inst)
             (count shapes-main)
             (count unique-refs)))
    (run! main-exists? shapes-inst)

    [shapes-inst shapes-main component]))

(defn resolve-instance-and-main-allow-dangling
  "Get the shape with the given id and all its children, and also
   the main component and all its shapes. Allows shapes with the
   corresponding component shape missing."
  [page root-inst-id libraries]
  (let [root-inst     (ctn/get-shape page root-inst-id)

        component     (ctf/get-component libraries (:component-id root-inst))

        shapes-inst   (cph/get-children-with-self (:objects page) root-inst-id)
        shapes-main   (cph/get-children-with-self (:objects component) (:shape-ref root-inst))

        unique-refs   (into #{} (map :shape-ref) shapes-inst)

        main-exists?  (fn [shape]
                        (let [component-shape
                              (ctn/get-component-shape (:objects page) shape)

                              component
                              (ctf/get-component libraries (:component-id component-shape))

                              main-shape
                              (ctn/get-shape component (:shape-ref shape))]

                        (t/is (some? main-shape))))]

    ;; Validate that the instance tree is well constructed
    (check-instance-root (first shapes-inst))

    [shapes-inst shapes-main component]))

(defn resolve-component
  "Get the component with the given id and all its shapes." 
  [page component-id libraries]
  (let [component   (ctf/get-component libraries component-id)
        root-main   (ctk/get-component-root component)
        shapes-main (cph/get-children-with-self (:objects component) (:id root-main))]

    ;; Validate that the component tree is well constructed
    (run! check-noninstance shapes-main)

    [shapes-main component]))

