;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers.libraries
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.main.data.helpers :as dsh]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]))

;; ---- Helpers to manage libraries and synchronization

(defn is-main-instance-root
  [shape]
  (t/is (nil? (:shape-ref shape)))
  (t/is (some? (:component-id shape)))
  (t/is (= (:component-root shape) true)))

(defn is-main-instance-subroot
  [shape]
  (t/is (some? (:component-id shape)))       ; shape-ref may or may be not nil
  (t/is (nil? (:component-root shape))))

(defn is-main-instance-child
  [shape]
  (t/is (nil? (:component-id shape)))        ; shape-ref may or may be not nil
  (t/is (nil? (:component-file shape)))
  (t/is (nil? (:component-root shape))))

(defn is-main-instance-inner
  [shape]
  (if (some? (:component-id shape))
    (is-main-instance-subroot shape)
    (is-main-instance-child shape)))

(defn is-instance-root
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (some? (:component-id shape)))
  (t/is (= (:component-root shape) true)))

(defn is-instance-subroot
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (some? (:component-id shape)))
  (t/is (nil? (:component-root shape))))

(defn is-instance-child
  [shape]
  (t/is (some? (:shape-ref shape)))
  (t/is (nil? (:component-id shape)))
  (t/is (nil? (:component-file shape)))
  (t/is (nil? (:component-root shape))))

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
  (t/is (nil? (:component-root shape)))
  (t/is (nil? (:remote-synced shape)))
  (t/is (nil? (:touched shape))))

(defn is-from-file
  [shape file]
  (t/is (= (:component-file shape)
           (:id file))))

(defn resolve-instance
  "Get the shape with the given id and all its children, and
   verify that they are a well constructed instance tree."
  [state root-id]
  (let [page   (thp/current-page state)
        shapes (cfh/get-children-with-self (:objects page)
                                           root-id)]
    (is-instance-root (first shapes))
    (run! is-instance-inner (rest shapes))

    shapes))

(defn resolve-noninstance
  "Get the shape with the given id and all its children, and
   verify that they are not a component instance."
  [state root-id]
  (let [page   (thp/current-page state)
        shapes (cfh/get-children-with-self (:objects page)
                                           root-id)]
    (run! is-noninstance shapes)

    shapes))

(defn resolve-instance-and-main
  "Get the shape with the given id and all its children, and also
   the main component and all its shapes."
  ([state root-inst-id]
   (resolve-instance-and-main state root-inst-id false))

  ([state root-inst-id subinstance?]
   (let [page           (thp/current-page state)
         root-inst      (ctn/get-shape page root-inst-id)
         main-instance? (:main-instance root-inst)

         libs           (dsh/lookup-libraries state)
         component      (ctf/get-component libs (:component-file root-inst) (:component-id root-inst))
         library        (ctf/get-component-library libs root-inst)

         shapes-inst    (cfh/get-children-with-self (:objects page) root-inst-id)
         shapes-main    (ctf/get-component-shapes (:data library) component)
         unique-refs    (into #{} (map :shape-ref) shapes-inst)

         main-exists?  (fn [shape]
                         (let [main-shape (ctf/get-ref-shape (:data library) component shape)]
                           (t/is (some? main-shape))))]

     ;; Validate that the instance tree is well constructed
     (if main-instance?
       (do
         (if subinstance?
           (is-main-instance-subroot (first shapes-inst))
           (is-main-instance-root (first shapes-inst)))
         (run! is-main-instance-inner (rest shapes-inst)))
       (do
         (if subinstance?
           (is-instance-subroot (first shapes-inst))
           (is-instance-root (first shapes-inst)))
         (run! is-instance-inner (rest shapes-inst))))

     (t/is (= (count shapes-inst) (count shapes-main)))
     (when-not main-instance?
       (t/is (= (count shapes-inst) (count unique-refs)))
       (run! main-exists? shapes-inst))

     [shapes-inst shapes-main component])))

(defn resolve-instance-and-main-allow-dangling
  "Get the shape with the given id and all its children, and also
   the main component and all its shapes. Allows shapes with the
   corresponding component shape missing."
  [state root-inst-id]
  (let [page          (thp/current-page state)
        root-inst     (ctn/get-shape page root-inst-id)

        libs          (dsh/lookup-libraries state)
        component     (ctf/get-component libs (:component-file root-inst) (:component-id root-inst))
        library       (ctf/get-component-library libs root-inst)

        shapes-inst   (cfh/get-children-with-self (:objects page) root-inst-id)
        shapes-main   (ctf/get-component-shapes (:data library) component)]

    ;; Validate that the instance tree is well constructed
    (is-instance-root (first shapes-inst))

    [shapes-inst shapes-main component]))

(defn resolve-component
  "Get the component with the given id and all its shapes."
  [state component-file component-id]
  (let [libs        (dsh/lookup-libraries state)
        component   (ctf/get-component libs component-file component-id)
        library     (ctf/get-component-library libs component)
        shapes-main (ctf/get-component-shapes (:data library) component)]

    ;; Validate that the component tree is well constructed
    (run! is-noninstance shapes-main)

    [shapes-main component]))
