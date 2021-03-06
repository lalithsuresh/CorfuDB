package org.corfudb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a mutatorAccessor, which is an method that modifies the state
 * of an object, then reads back the result of that modification.
 *
 * Created by mwei on 1/7/16.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface MutatorAccessor {
    /** The name of the mutator, which will be written to the log.
     * @return The name of the mutator. */
    String name() default "";

    /** The name of the function to undo this mutation, which needs to belong
     * to the same object.
     * @return The name of the undo functino.
     */
    String undoFunction() default "";

    /** The name of the function which will be called prior to applying this
     * mutator, which will be used to generate an undo record.
     * @return The name of the undoRecord function.
     */
    String undoRecordFunction() default "";

    /** The name of the function used to generate conflict parameters, which
     * will be used to generate conflict information.
     * @return  The name of a conflict generation function.
     */
    String conflictParameterFunction() default "";

    /** Whether this mutator resets the state of this object. Typically used
     * for methods like clear().
     * @return True, if the mutator resets the object.
     */
    boolean reset() default false;

    /** Whether or not we should generate an upcall for this mutator. If set to
     * true, no upcall will be generated - this is typically used when
     * providing a mutator-only version of a mutatorAccessor
     * (for example, "blindPut" and "put").
     * @return True, if no upcall should be generated.
     */
    boolean noUpcall() default false;
}
