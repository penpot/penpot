(ns app.test-helpers.libraries
  (:require
   [cljs.test :as t :include-macros true]
   [cljs.pprint :refer [pprint]]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.main.data.workspace.state-helpers :as wsh]
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

(defn is-instance-child
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (nil? (:component-id shape)))
  (t/is (nil? (:component-file shape)))
  (t/is (nil? (:component-root? shape))))

(defn is-instance-inner
  [shape]
  (if (some? (:component-id shape))
    (is-instance-subroot shape)
    (is-instance-child shape)))

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

(defn resolve-instance
  "Get the shape with the given id and all its children, and
   verify that they are a well constructed instance tree."
  [state root-inst-id]
  (let [page        (thp/current-page state)
        root-inst   (ctn/get-shape page root-inst-id)
        shapes-inst (cph/get-children-with-self (:objects page)
                                                root-inst-id)]
    (is-instance-root (first shapes-inst))
    (run! is-instance-inner (rest shapes-inst))

    shapes-inst))

(defn resolve-noninstance
  "Get the shape with the given id and all its children, and
   verify that they are not a component instance."
  [state root-inst-id]
  (let [page        (thp/current-page state)
        root-inst   (ctn/get-shape page root-inst-id)
        shapes-inst (cph/get-children-with-self (:objects page)
                                                root-inst-id)]
    (run! is-noninstance shapes-inst)

    shapes-inst))

(defn resolve-instance-and-main
  "Get the shape with the given id and all its children, and also
   the main component and all its shapes."
  ([state root-inst-id]
   (resolve-instance-and-main state root-inst-id false))

  ([state root-inst-id subinstance?]
   (let [page          (thp/current-page state)
         root-inst     (ctn/get-shape page root-inst-id)

         libs          (wsh/get-libraries state)
         component     (ctf/get-component libs (:component-id root-inst))

         shapes-inst   (cph/get-children-with-self (:objects page) root-inst-id)
         shapes-main   (cph/get-children-with-self (:objects component) (:shape-ref root-inst))

         unique-refs   (into #{} (map :shape-ref) shapes-inst)

         main-exists?  (fn [shape]
                         (let [component-shape
                               (ctn/get-component-shape (:objects page) shape)

                               component
                               (ctf/get-component libs (:component-id component-shape))

                               main-shape
                               (ctn/get-shape component (:shape-ref shape))]

                           (t/is (some? main-shape))))]

     ;; Validate that the instance tree is well constructed
     (if subinstance?
       (is-instance-subroot (first shapes-inst))
       (is-instance-root (first shapes-inst)))
     (run! is-instance-inner (rest shapes-inst))
     (t/is (= (count shapes-inst)
              (count shapes-main)
              (count unique-refs)))
     (run! main-exists? shapes-inst)

     [shapes-inst shapes-main component])))

(defn resolve-instance-and-main-allow-dangling
  "Get the shape with the given id and all its children, and also
   the main component and all its shapes. Allows shapes with the
   corresponding component shape missing."
  [state root-inst-id]
  (let [page          (thp/current-page state)
        root-inst     (ctn/get-shape page root-inst-id)

        libs          (wsh/get-libraries state)
        component     (ctf/get-component libs (:component-id root-inst))

        shapes-inst   (cph/get-children-with-self (:objects page) root-inst-id)
        shapes-main   (cph/get-children-with-self (:objects component) (:shape-ref root-inst))

        unique-refs   (into #{} (map :shape-ref) shapes-inst)

        main-exists?  (fn [shape]
                        (let [component-shape
                              (ctn/get-component-shape (:objects page) shape)

                              component
                              (ctf/get-component libs (:component-id component-shape))

                              main-shape
                              (ctn/get-shape component (:shape-ref shape))]

                        (t/is (some? main-shape))))]

    ;; Validate that the instance tree is well constructed
    (is-instance-root (first shapes-inst))

    [shapes-inst shapes-main component]))

(defn resolve-component
  "Get the component with the given id and all its shapes." 
  [state component-id]
  (let [page        (thp/current-page state)
        libs        (wsh/get-libraries state)
        component   (ctf/get-component libs component-id)
        root-main   (ctk/get-component-root component)
        shapes-main (cph/get-children-with-self (:objects component) (:id root-main))]

    ;; Validate that the component tree is well constructed
    (run! is-noninstance shapes-main)

    [shapes-main component]))

