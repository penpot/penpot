;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.media-test
  (:require
   [app.common.exceptions :as ex]
   [app.media :as media]
   [app.media.svg :as svg]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]))

(t/use-fixtures :once th/state-init)

(t/deftest info-jpeg
  (t/testing "info on valid JPEG returns dimensions and mime type"
    (let [path  (th/tempfile "backend_tests/test_files/sample.jpg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/jpeg"}})]
      (t/is (pos? (:width info)))
      (t/is (pos? (:height info)))
      (t/is (= "image/jpeg" (:mtype info)))
      (t/is (pos? (:size info)))
      (t/is (some? (:ts info))))))

(t/deftest info-png
  (t/testing "info on valid PNG returns dimensions and mime type"
    (let [path  (th/tempfile "backend_tests/test_files/sample.png")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/png"}})]
      (t/is (pos? (:width info)))
      (t/is (pos? (:height info)))
      (t/is (= "image/png" (:mtype info))))))

(t/deftest info-webp
  (t/testing "info on valid WebP returns dimensions and mime type"
    (let [path  (th/tempfile "backend_tests/test_files/sample.webp")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/webp"}})]
      (t/is (pos? (:width info)))
      (t/is (pos? (:height info)))
      (t/is (= "image/webp" (:mtype info))))))

(t/deftest info-svg
  (t/testing "info on valid SVG returns dimensions from viewBox"
    (let [path  (th/tempfile "backend_tests/test_files/sample1.svg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/svg+xml"}})]
      (t/is (pos? (:width info)))
      (t/is (pos? (:height info))))))

(t/deftest sanitize-svg-script-tag
  (t/testing "sanitize-svg removes script tags"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><script>alert('xss')</script><rect width=\"50\" height=\"50\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "<script>")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-event-handlers
  (t/testing "sanitize-svg removes event handler attributes"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" onload=\"alert('xss')\"><rect width=\"50\" height=\"50\" onmouseover=\"alert('xss')\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "onload")))
      (t/is (not (str/includes? result "onmouseover")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-javascript-href
  (t/testing "sanitize-svg removes javascript: URLs from href attributes"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"100\" height=\"100\"><a xlink:href=\"javascript:alert('xss')\"><rect width=\"50\" height=\"50\"/></a></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "javascript:")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<a")))))

(t/deftest sanitize-svg-foreign-object
  (t/testing "sanitize-svg removes foreignObject elements"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><foreignObject width=\"100\" height=\"100\"><body xmlns=\"http://www.w3.org/1999/xhtml\"><script>alert('xss')</script></body></foreignObject><rect width=\"50\" height=\"50\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "foreignObject")))
      (t/is (not (str/includes? result "<script>")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-clean-content
  (t/testing "sanitize-svg preserves clean SVG content"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"50\" height=\"50\" fill=\"red\"/><circle cx=\"75\" cy=\"75\" r=\"20\" fill=\"blue\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (str/includes? result "<rect"))
      (t/is (str/includes? result "<circle"))
      (t/is (str/includes? result "fill=\"red\""))
      (t/is (str/includes? result "fill=\"blue\"")))))

(t/deftest sanitize-svg-invalid-svg-rejected
  (t/testing "sanitize-svg rejects malformed SVG input"
    (let [svg "<svg><not-closed>"]
      (t/is (thrown-with-msg? Exception #"SVG parsing failed during sanitization"
                              (svg/sanitize-svg svg))))))

(t/deftest sanitize-svg-preserves-xlink
  (t/testing "sanitize-svg preserves legitimate xlink:href attributes"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"100\" height=\"100\"><use xlink:href=\"#icon\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (str/includes? result "xlink:href"))
      (t/is (str/includes? result "#icon")))))

(t/deftest sanitize-svg-javascript-href-whitespace
  (t/testing "sanitize-svg catches javascript: URLs with leading whitespace"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><a href=\" javascript:alert('xss')\"><rect width=\"50\" height=\"50\"/></a></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "javascript:")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<a")))))

(t/deftest sanitize-svg-nested-script
  (t/testing "sanitize-svg removes script tags from nested elements"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><g><script>alert('xss')</script></g></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "<script")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<g")))))

(t/deftest sanitize-svg-smil-bypass
  (t/testing "sanitize-svg removes SMIL animation elements that can set on* attrs"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"100\" height=\"100\" id=\"r\"/><set attributeName=\"onmouseover\" to=\"alert('xss')\" xlink:href=\"#r\" begin=\"0s\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "<set")))
      (t/is (not (str/includes? result "onmouseover")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-namespace-prefixed-script
  (t/testing "sanitize-svg removes script tags written with a namespace prefix bound to the SVG namespace"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:x=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><x:script>alert('xss')</x:script><rect width=\"50\" height=\"50\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "<x:script")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-arbitrary-prefixed-script
  (t/testing "sanitize-svg removes script tags written with an arbitrary namespace prefix"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:p=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><p:script>alert('xss')</p:script><rect width=\"50\" height=\"50\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "<p:script")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-nested-prefixed-script
  (t/testing "sanitize-svg removes namespace-prefixed script tags from nested elements"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:x=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><g><x:script>alert('xss')</x:script></g></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "<x:script")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<g")))))

(t/deftest sanitize-svg-prefixed-dangerous-tags
  (t/testing "sanitize-svg removes dangerous animation elements written with a namespace prefix"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:x=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><x:foreignObject width=\"10\" height=\"10\"/><x:set attributeName=\"onmouseover\" to=\"alert('xss')\"/><x:animate attributeName=\"fill\" to=\"red\"/><x:animateTransform attributeName=\"transform\"/><rect width=\"50\" height=\"50\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "foreignObject")))
      (t/is (not (str/includes? result "<x:set")))
      (t/is (not (str/includes? result "<x:animate")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-prefixed-javascript-href
  (t/testing "sanitize-svg removes javascript: URLs from href attributes written with a namespace prefix"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:x=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><a x:href=\"javascript:alert('xss')\"><rect width=\"50\" height=\"50\"/></a></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "javascript:")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<a")))))

(t/deftest sanitize-svg-prefixed-event-handlers
  (t/testing "sanitize-svg removes event handler attributes written with a namespace prefix"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:x=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" x:onload=\"alert('xss')\"><rect width=\"50\" height=\"50\" x:onclick=\"alert('xss')\"/></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "onload")))
      (t/is (not (str/includes? result "onclick")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-prefixed-xlink-namespace-href
  (t/testing "sanitize-svg removes javascript: URLs from href written with a prefix bound to the xlink namespace"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:y=\"http://www.w3.org/1999/xlink\" width=\"100\" height=\"100\"><a y:href=\"javascript:alert('xss')\"><rect width=\"50\" height=\"50\"/></a></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (not (str/includes? result "javascript:")))
      (t/is (not (str/includes? result "alert")))
      (t/is (str/includes? result "<a")))))

(t/deftest sanitize-svg-preserved-prefixed-attrs
  (t/testing "sanitize-svg preserves legitimate attributes written with a namespace prefix"
    (let [svg "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xml=\"http://www.w3.org/XML/1998/namespace\"><text xml:space=\"preserve\">hola</text></svg>"
          result (svg/sanitize-svg svg)]
      (t/is (str/includes? result "xml:space"))
      (t/is (str/includes? result "hola")))))

;; --- SVG sanitization: escaping on serialization
;;
;; The sanitizer filters dangerous elements and attributes by name, so the
;; escaping happens when the tree is written back. A payload hidden in an
;; attribute value or in a text node must not become live markup again.

(def ^:private clean-svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><circle r=\"4\"/></svg>")

(def ^:private evil-attribute-svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\" id=\"x' onload='alert(1)\"><rect width=\"10\" height=\"10\"/></svg>")

(def ^:private evil-text-svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><text>&lt;/text&gt;&lt;script&gt;alert(1)&lt;/script&gt;&lt;text&gt;</text></svg>")

(def ^:private evil-cdata-svg
  "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><text><![CDATA[</text><script>alert(1)</script><text>]]></text></svg>")

(def ^:private evil-javascript-svg
  "An SVG with a javascript: URL obfuscated with a tab inside the scheme."
  "<svg xmlns=\"http://www.w3.org/2000/svg\"><a href=\"java&#x09;script:alert(1)\">x</a></svg>")

(def ^:private xhtml-document
  "A document that is not an SVG at all, with HTML only elements."
  "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><iframe srcdoc=\"&lt;script&gt;alert(1)&lt;/script&gt;\"/><object data=\"data:text/html,x\"/><meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\"/></body></html>")

(t/deftest sanitize-svg-escapes-attribute-values
  (t/testing "an event handler smuggled in an attribute value is escaped"
    (let [result (svg/sanitize-svg evil-attribute-svg)
          parsed (svg/parse-svg result)]
      (t/is (not (contains? (:attrs parsed) :onload)))
      (t/is (= "x' onload='alert(1)"
               (get (:attrs parsed) :id)))
      (t/is (str/includes? result "&apos;")))))

(t/deftest sanitize-svg-escapes-text-content
  (t/testing "entity encoded markup in a text node is escaped"
    (let [result (svg/sanitize-svg evil-text-svg)]
      (t/is (not (str/includes? result "<script")))
      (t/is (str/includes? result "&lt;script&gt;")))))

(t/deftest sanitize-svg-escapes-cdata-content
  (t/testing "markup hidden in a CDATA node is escaped"
    (let [result (svg/sanitize-svg evil-cdata-svg)]
      (t/is (not (str/includes? result "<script")))
      (t/is (str/includes? result "&lt;script&gt;")))))

(t/deftest sanitize-svg-escapes-ampersands
  (t/testing "ampersands in text and attribute values are escaped"
    (let [result (svg/sanitize-svg "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"a&amp;b\"><text>a &amp; b</text></svg>")]
      (t/is (str/includes? result "a &amp; b"))
      (t/is (= "a&b"
               (get-in (svg/parse-svg result) [:attrs :id]))))))

(t/deftest sanitize-svg-output-is-valid-xml
  (t/testing "the sanitized output can be parsed back"
    (doseq [payload [clean-svg
                     evil-attribute-svg
                     evil-text-svg
                     evil-cdata-svg]]
      (t/is (some? (svg/parse-svg (svg/sanitize-svg payload)))))))

(t/deftest sanitize-svg-rejects-non-svg-documents
  (t/testing "a document that is not an svg document is rejected"
    (doseq [payload [xhtml-document
                     ;; an svg element declared in the xhtml namespace
                     "<svg xmlns=\"http://www.w3.org/1999/xhtml\"><rect/></svg>"
                     ;; an svg element without any namespace
                     "<svg><rect/></svg>"]]
      (let [error (try (svg/sanitize-svg payload) nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? error))
        (t/is (th/ex-of-type? error :validation))
        (t/is (th/ex-of-code? error :invalid-svg-file))))))

(t/deftest sanitize-svg-rejects-non-svg-root-local-name
  (t/testing "a root in the svg namespace whose local name is not svg is rejected"
    (doseq [payload ["<rect xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>"
                     "<html xmlns=\"http://www.w3.org/2000/svg\"></html>"]]
      (let [error (try (svg/sanitize-svg payload) nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? error))
        (t/is (th/ex-of-code? error :invalid-svg-file))))))

(t/deftest sanitize-svg-reports-non-svg-root
  (t/testing "a root without the svg namespace reports it, not a serializer failure"
    (let [error (try (svg/sanitize-svg "<svg><rect/></svg>") nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-code? error :invalid-svg-file))
      (t/is (str/includes? (ex-message error) "not an svg document")))))

(t/deftest sanitize-svg-accepts-svg-roots
  (t/testing "an svg root in the svg namespace is accepted"
    (t/is (some? (svg/sanitize-svg "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>"))))
  (t/testing "an svg root with a namespace prefix is accepted"
    (t/is (some? (svg/sanitize-svg "<svg:svg xmlns:svg=\"http://www.w3.org/2000/svg\"><svg:rect/></svg:svg>")))))

(t/deftest sanitize-svg-upload-rejects-invalid-svg
  (t/testing "the helper rejects an upload that does not hold a valid svg"
    (doseq [content ["<svg xmlns=\"http://www.w3.org/2000/svg\"><rect>"
                     xhtml-document]]
      (th/with-temp-file [path content]
        (let [upload {:filename "evil.svg"
                      :path path
                      :mtype "image/svg+xml"
                      :size (fs/size path)}
              error  (try (svg/sanitize-svg-upload upload) nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (t/is (some? error))
          (t/is (th/ex-of-code? error :invalid-svg-file)))))))

(t/deftest sanitize-svg-rejects-doctype
  (t/testing "a document with a doctype and an internal subset is rejected"
    (let [error (try (svg/sanitize-svg
                      "<!DOCTYPE svg [<!ENTITY x \"y\">]><svg xmlns=\"http://www.w3.org/2000/svg\"><text>&x;</text></svg>")
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-code? error :invalid-svg-file)))))

(t/deftest sanitize-svg-escapes-double-quote
  (t/testing "a double quote in an attribute value is escaped"
    (let [result (svg/sanitize-svg "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"a&quot;b\"/>")
          parsed (svg/parse-svg result)]
      (t/is (str/includes? result "&quot;"))
      (t/is (= "a\"b" (get (:attrs parsed) :id))))))

(t/deftest sanitize-svg-filters-prefixed-root-children
  (t/testing "dangerous elements and attributes are removed inside a prefixed root"
    (let [result (svg/sanitize-svg "<svg:svg xmlns:svg=\"http://www.w3.org/2000/svg\"><svg:script>alert(1)</svg:script><svg:rect onload=\"alert(1)\"/></svg:svg>")
          parsed (svg/parse-svg result)]
      (t/is (not (str/includes? result "script")))
      (t/is (not (str/includes? result "onload")))
      (t/is (= 1 (count (:content parsed)))))))

(t/deftest sanitize-svg-rejects-wrong-namespace
  (t/testing "a root that is not in the svg namespace is rejected"
    (doseq [payload ["<svg xmlns:foo=\"http://www.w3.org/2000/svg\"><rect/></svg>"
                     "<svg xmlns=\"\"><rect/></svg>"
                     "<svg xmlns=\" http://www.w3.org/2000/svg \"><rect/></svg>"
                     "<svg xmlns=\"http://www.w3.org/2000/svg&#9;\"><rect/></svg>"]]
      (let [error (try (svg/sanitize-svg payload) nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (t/is (some? error))
        (t/is (th/ex-of-code? error :invalid-svg-file))))))

;; --- SVG sanitization: namespace in scope
;;
;; The XML parser does not process namespaces, so a child can rebind the
;; default namespace to XHTML and smuggle live HTML (iframe/srcdoc/meta).
;; The sanitizer drops any element that is not in the SVG namespace.

(def ^:private xhtml-child-svg
  "An SVG whose root is in the SVG namespace but whose child rebinds the
   default namespace to XHTML and embeds live HTML."
  "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><g xmlns=\"http://www.w3.org/1999/xhtml\"><iframe srcdoc=\"&lt;script&gt;alert(1)&lt;/script&gt;\"/><meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\"/></g><rect width=\"10\" height=\"10\"/></svg>")

(t/deftest sanitize-svg-drops-non-svg-namespace-subtrees
  (t/testing "a child that rebinds the default namespace to XHTML is dropped"
    (let [result (svg/sanitize-svg xhtml-child-svg)]
      (t/is (not (str/includes? result "iframe")))
      (t/is (not (str/includes? result "meta")))
      (t/is (not (str/includes? result "srcdoc")))
      (t/is (not (str/includes? result "http-equiv")))
      (t/is (str/includes? result "<rect")))))

(t/deftest sanitize-svg-drops-prefixed-non-svg-namespace-subtrees
  (t/testing "a child whose prefix is bound to XHTML is dropped"
    (let [result (svg/sanitize-svg
                  "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:h=\"http://www.w3.org/1999/xhtml\"><h:iframe srcdoc=\"&lt;script&gt;alert(1)&lt;/script&gt;\"/></svg>")]
      (t/is (not (str/includes? result "iframe")))
      (t/is (not (str/includes? result "srcdoc"))))))

(t/deftest sanitize-svg-keeps-prefixed-svg-namespace-subtrees
  (t/testing "a child whose prefix is bound to the SVG namespace is kept"
    (let [result (svg/sanitize-svg
                  "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:s=\"http://www.w3.org/2000/svg\"><s:rect width=\"10\" height=\"10\"/></svg>")]
      (t/is (str/includes? result "<s:rect")))))

(def ^:private default-prefix-xhtml-svg
  "An SVG whose root declares a prefix named `default` bound to XHTML."
  "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:default=\"http://www.w3.org/1999/xhtml\"><default:iframe srcdoc=\"&lt;script&gt;alert(1)&lt;/script&gt;\"/><default:meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\"/></svg>")

(t/deftest sanitize-svg-drops-default-prefix-xhtml-subtrees
  (t/testing "a prefix named default bound to XHTML is not the default namespace"
    (let [result (svg/sanitize-svg default-prefix-xhtml-svg)]
      (t/is (not (str/includes? result "default:iframe")))
      (t/is (not (str/includes? result "default:meta")))
      (t/is (not (str/includes? result "srcdoc")))
      (t/is (not (str/includes? result "http-equiv"))))))

(def ^:private xmlns-onload-svg
  "An SVG that declares a prefix named like an event handler."
  "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:onload=\"http://www.w3.org/2000/svg\"><onload:rect width=\"10\" height=\"10\"/></svg>")

(t/deftest sanitize-svg-keeps-xmlns-declarations
  (t/testing "a namespace declaration whose local name looks like an event handler is kept"
    (let [result (svg/sanitize-svg xmlns-onload-svg)]
      (t/is (str/includes? result "xmlns:onload"))
      (t/is (str/includes? result "<onload:rect")))))

(t/deftest sanitize-svg-drops-html-only-tags
  (t/testing "HTML-only elements written in the svg namespace are dropped"
    (doseq [tag ["iframe" "meta" "embed" "link" "base"]]
      (let [result (svg/sanitize-svg
                    (str "<svg xmlns=\"http://www.w3.org/2000/svg\"><" tag " src=\"https://evil\"/></svg>"))]
        (t/is (not (str/includes? result (str "<" tag))))))))

(t/deftest sanitize-svg-javascript-href-control-char
  (t/testing "a javascript: URL with a control char inside the scheme is removed"
    (let [result (svg/sanitize-svg evil-javascript-svg)
          parsed (svg/parse-svg result)]
      (t/is (not (contains? (get-in parsed [:content 0 :attrs]) :href)))
      (t/is (not (str/includes? result "script:alert")))))
  (t/testing "a javascript: URL with a line break inside the scheme is removed"
    (let [result (svg/sanitize-svg "<svg xmlns=\"http://www.w3.org/2000/svg\"><a href=\"java&#x0a;script:alert(1)\">x</a></svg>")]
      (t/is (not (str/includes? result "script:alert"))))))

(t/deftest sanitize-svg-preserves-safe-href
  (t/testing "a safe href is preserved"
    (let [parsed (svg/parse-svg (svg/sanitize-svg "<svg xmlns=\"http://www.w3.org/2000/svg\"><a href=\"https://example.com/\">x</a></svg>"))]
      (t/is (= "https://example.com/"
               (get-in parsed [:content 0 :attrs :href]))))))

(t/deftest sanitize-svg-rejects-script-root
  (t/testing "a document whose root is a script is rejected as not an svg document"
    (let [error (try (svg/sanitize-svg "<script>alert(1)</script>") nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (t/is (th/ex-of-type? error :validation))
      (t/is (th/ex-of-code? error :invalid-svg-file))
      (t/is (str/includes? (ex-message error) "not an svg document")))))

(t/deftest sanitize-svg-is-idempotent
  (t/testing "sanitizing an already sanitized document does not change it"
    (doseq [payload [clean-svg
                     evil-attribute-svg
                     evil-text-svg
                     evil-cdata-svg
                     evil-javascript-svg]]
      (let [once (svg/sanitize-svg payload)]
        (t/is (= once (svg/sanitize-svg once)))))))

(t/deftest get-basic-info-from-svg-accepts-prefixed-root
  (t/testing "an svg root with a namespace prefix provides its dimensions"
    (let [info (svg/get-basic-info-from-svg
                (svg/parse-svg "<svg:svg xmlns:svg=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"80\"/>"))]
      (t/is (= 120 (:width info)))
      (t/is (= 80 (:height info))))))

(t/deftest get-basic-info-from-svg-rejects-non-svg
  (t/testing "a document whose root is not an svg element is rejected"
    (let [error (try (svg/get-basic-info-from-svg
                      (svg/parse-svg "<html xmlns=\"http://www.w3.org/1999/xhtml\"/>"))
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (t/is (some? error))
      (t/is (th/ex-of-code? error :unable-to-parse-svg)))))

(t/deftest sanitize-svg-upload-sanitizes-svg
  (t/testing "an SVG upload is replaced by a sanitized copy"
    (th/with-temp-file [path evil-attribute-svg]
      (let [upload {:filename "evil.svg"
                    :path path
                    :mtype "image/svg+xml"
                    :size (fs/size path)}
            result (svg/sanitize-svg-upload upload)
            parsed (svg/parse-svg (slurp (str (:path result))))]
        (t/is (not= path (:path result)))
        (t/is (not (contains? (:attrs parsed) :onload)))
        (t/is (= "x' onload='alert(1)"
                 (get (:attrs parsed) :id)))
        (t/is (= (fs/size (:path result)) (:size result)))))))

(t/deftest sanitize-svg-upload-passes-non-svg
  (t/testing "an upload that is not an SVG is returned untouched"
    (let [path   (th/tempfile "backend_tests/test_files/sample.jpg")
          upload {:filename "sample.jpg"
                  :path path
                  :mtype "image/jpeg"
                  :size 312043}]
      (t/is (= upload (svg/sanitize-svg-upload upload))))))

(t/deftest info-invalid-image
  (t/testing "info on invalid image raises error"
    (let [path (fs/create-tempfile :prefix "penpot-test-" :suffix ".jpg")]
      ;; Write garbage data
      (spit (str path) "not an image")
      (try
        (media/run th/*system* {:cmd :info
                                :input {:path path
                                        :mtype "image/jpeg"}})
        (t/is false "should have thrown")
        (catch Exception e
          (let [data (ex-data e)]
            (t/is (= :validation (:type data)))
            (t/is (= :invalid-image (:code data)))))
        (finally
          (fs/delete path))))))

(t/deftest generic-thumbnail
  (t/testing "generic-thumbnail produces a file of expected format"
    (let [path  (th/tempfile "backend_tests/test_files/sample.jpg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/jpeg"}})
          thumb (media/run th/*system* {:cmd :generic-thumbnail
                                        :input info
                                        :format :jpeg
                                        :quality 80
                                        :width 200
                                        :height 200})]
      (t/is (some? (:data thumb)))
      (t/is (pos? (:size thumb)))
      (t/is (= :jpeg (:format thumb)))
      (t/is (= "image/jpeg" (:mtype thumb)))
      ;; Verify the thumbnail file exists
      (t/is (fs/exists? (:data thumb))))))

(t/deftest profile-thumbnail
  (t/testing "profile-thumbnail produces a center-cropped file"
    (let [path  (th/tempfile "backend_tests/test_files/sample.jpg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/jpeg"}})
          thumb (media/run th/*system* {:cmd :profile-thumbnail
                                        :input info
                                        :format :jpeg
                                        :quality 85
                                        :width 128
                                        :height 128})]
      (t/is (some? (:data thumb)))
      (t/is (pos? (:size thumb)))
      (t/is (= :jpeg (:format thumb)))
      (t/is (= "image/jpeg" (:mtype thumb)))
      ;; Verify the thumbnail file exists
      (t/is (fs/exists? (:data thumb))))))

(t/deftest generic-thumbnail-webp
  (t/testing "generic-thumbnail can produce WebP format"
    (let [path  (th/tempfile "backend_tests/test_files/sample.jpg")
          info  (media/run th/*system* {:cmd :info
                                        :input {:path path
                                                :mtype "image/jpeg"}})
          thumb (media/run th/*system* {:cmd :generic-thumbnail
                                        :input info
                                        :format :webp
                                        :quality 80
                                        :width 200
                                        :height 200})]
      (t/is (= :webp (:format thumb)))
      (t/is (= "image/webp" (:mtype thumb))))))
