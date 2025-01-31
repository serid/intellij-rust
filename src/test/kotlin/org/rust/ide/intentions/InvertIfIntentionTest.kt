/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

class InvertIfIntentionTest : RsIntentionTestBase(InvertIfIntention::class) {
    fun `test if let unavailable`() = doUnavailableTest("""
        fn foo(a: Option<i32>) {
            if/*caret*/ let Some(x) = a {} else {}
        }
    """)

    fun `test if without condition unavailable`() = doUnavailableTest("""
        fn foo() {
            if /*caret*/ {} else {}
        }
    """)

    fun `test if without else branch unavailable`() = doUnavailableTest("""
        fn foo(a: i32) {
            if/*caret*/ a == 10  {}
        }
    """)

    fun `test if without then branch unavailable`() = doUnavailableTest("""
        fn foo(a: i32) {
            if a/*caret*/ == 10 else {}
        }
    """)

    fun `test availability range`() = checkAvailableInSelectionOnly("""
        fn foo() {
            <selection>if</selection> 2 == 2 { Ok(()) } else { Err(()) }
        }
    """)

    fun `test simple inversion`() = doAvailableTest("""
        fn foo() {
            if/*caret*/ 2 == 2 { Ok(()) } else { Err(()) }
        }
    """, """
        fn foo() {
            if 2 != 2 { Err(()) } else { Ok(()) }
        }
    """)

    // `!(2 == 2)` can be later simplified to `2 != 2` by user via `Simplify boolean expression` intention
    fun `test simple inversion parens`() = doAvailableTest("""
        fn foo() {
            if/*caret*/ (2 == 2) { Ok(()) } else { Err(()) }
        }
    """, """
        fn foo() {
            if !(2 == 2) { Err(()) } else { Ok(()) }
        }
    """)

    fun `test conjunction condition`() = doAvailableTest("""
        fn foo() {
            if/*caret*/ 2 == 2 && 3 == 3 { Ok(()) } else { Err(()) }
        }
    """, """
        fn foo() {
            if 2 != 2 || 3 != 3 { Err(()) } else { Ok(()) }
        }
    """)

    fun `test complex condition`() = doAvailableTest("""
        fn foo() {
            if/*caret*/ 2 == 2 && (3 == 3 || 4 == 4) && (5 == 5) { Ok(()) } else { Err(()) }
        }
    """, """
        fn foo() {
            if 2 != 2 || !(3 == 3 || 4 == 4) || (5 != 5) { Err(()) } else { Ok(()) }
        }
    """)

    fun `test bool literal inversion`() = doAvailableTest("""
        fn foo() {
            if/*caret*/ true { Ok(()) } else { Err(()) }
        }
    """, """
        fn foo() {
            if false { Err(()) } else { Ok(()) }
        }
    """)

    fun `test simple inversion strange formatting`() = doAvailableTest("""
        fn foo() {
            if/*caret*/ 2 == 2 {
                Ok(())
            } else { Err(()) }
        }
    """, """
        fn foo() {
            if 2 != 2 { Err(()) } else {
                Ok(())
            }
        }
    """)

    fun `test very path expr condition`() = doAvailableTest("""
        fn foo(cond: bool) {
            if/*caret*/ cond {
                Ok(())
            } else {
                Err(())
            }
        }
    """, """
        fn foo(cond: bool) {
            if !cond {
                Err(())
            } else {
                Ok(())
            }
        }
    """)
}
