;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.style
  "A fonts loading macros."
  (:require
   [app.common.exceptions :as ex]
   [clojure.core :as c]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [rumext.v2.compiler :as mfu]))

;; Should match with the `ROOT_NAME` constant in gulpfile.js
(def ROOT-NAME "app")

(def ^:dynamic *css-prefix* nil)

(defn get-prefix
  ;; Calculates the css-modules prefix given the filename
  ;; should be the same as the calculation inside the `gulpfile.js`
  [fname]
  (let [file (io/file fname)
        parts
        (->> (str/split (.getParent file) #"/")
             (drop-while #(not= % ROOT-NAME))
             (rest)
             (str/join "_"))]
    (str parts "_" (subs (.getName file) 0 (- (count (.getName file)) 5)) "__")))

(def ^:private xform-css
  (keep (fn [k]
          (cond
            (keyword? k)
            (let [knm (name k)
                  kns (namespace k)]
              (case kns
                "global" knm
                (str *css-prefix* knm)))

            (string? k)
            k))))

(defmacro css*
  "Just coerces all params to strings and concats them with
  space. Used mainly to set a set of classes together."
  [& selectors]
  (->> selectors
       (map name)
       (interpose " ")
       (apply str)))

(defn- read-json-file
  [path]
  (or (ex/ignoring (-> (slurp (io/resource path))
                       (json/read-str :key-fn keyword)))
      {}))

(defmacro css
  "Uses a css-modules defined data for real class lookup, then concat
  all classes with space in the same way as `css*`."
  [& selectors]
  (let [fname (-> *ns* meta :file)
        prefix (get-prefix fname)]
    (if (symbol? (first selectors))
      `(if ~(with-meta (first selectors) {:tag 'boolean})
         (css* ~@(binding [*css-prefix* prefix]
                   (into [] xform-css (rest selectors))))
         (css* ~@(rest selectors)))
      `(css* ~@(binding [*css-prefix* prefix]
                 (into [] xform-css selectors))))))

(defmacro styles
  []
  ;; Get the associated styles will be module.cljs => module.css.json
  (let [fname (-> *ns* meta :file)
        path  (str (subs fname 0 (- (count fname) 4)) "css.json")]
    (read-json-file path)))

(def ^:private xform-css-case
  (comp
   (partition-all 2)
   (keep (fn [[k v]]
           (let [cls (cond
                       (keyword? k)
                       (let [knm (name k)
                             kns (namespace k)]
                         (case kns
                           "global"  knm
                           (str *css-prefix* knm)))

                       (string? k)
                       k)]
             (when cls
               (cond
                 (true? v)  cls
                 (false? v) ""
                 :else     `(if ~v ~cls ""))))))
   (interpose " ")))

;; A macro that simplifies setting up classes using css-modules and enhaces the
;; migration process from the old approach.
;;
;; Using this as example:
;;
;;  (stl/css-case new-css-system
;;                :left-settings-bar    true
;;                :global/two-row       (<= size 300))
;;
;; The first argument to the `css-case` macro is optional an if you don't
;; provide it, it will behave in the same ways as if the `new-css-system` has
;; value of `true`.
;;
;; The non-namespaces keywords passed are treated conditionally on the
;; `new-css-system` value. If is `true`, it will perform a lookup on modules for
;; corresponding (hashed) class-name; if no class name is found, the keyword
;; will be stringigied and used as-is (with no changes). If the `new-css-system`
;; is false, it will perform the same operation as if no class is found on
;; modules (leaving it as string with no modification).
;;
;; Later, we have two modifiers (namespaces): `global` which specifies
;; explicitly that no modules lookup should be performed; and `old-css` which
;; only puts the class if `new-css-system` is `false`.
;;
;; NOTE: the same behavior applies to the `css` macro

(defmacro css-case
  [& params]
  (let [fname (-> *ns* meta :file)
        prefix (get-prefix fname)]

    (if (symbol? (first params))
      `(if ~(with-meta (first params) {:tag 'boolean})
         ~(binding [*css-prefix* prefix]
            (-> (into [] xform-css-case (rest params))
                (mfu/compile-concat :safe? false)))
         ~(-> (into [] xform-css-case (rest params))
              (mfu/compile-concat :safe? false)))
      `~(binding [*css-prefix* prefix]
          (-> (into [] xform-css-case params)
              (mfu/compile-concat :safe? false))))))

(defmacro css-case*
  [& params]
  (-> (into [] xform-css-case params)
      (mfu/compile-concat  :safe? false)))
