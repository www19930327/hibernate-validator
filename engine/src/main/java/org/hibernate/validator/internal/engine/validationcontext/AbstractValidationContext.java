/*
 * Hibernate Validator, declare and validate application constraints
 *
 * License: Apache License, Version 2.0
 * See the license.txt file in the root directory or <http://www.apache.org/licenses/LICENSE-2.0>.
 */
package org.hibernate.validator.internal.engine.validationcontext;

import static org.hibernate.validator.internal.util.CollectionHelper.newHashSet;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.validation.ConstraintValidatorFactory;
import javax.validation.ConstraintViolation;
import javax.validation.Path;
import javax.validation.TraversableResolver;
import javax.validation.ValidationException;
import javax.validation.Validator;
import javax.validation.metadata.ConstraintDescriptor;

import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorInitializationContext;
import org.hibernate.validator.internal.engine.MessageInterpolatorContext;
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorContextImpl;
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintValidatorManager;
import org.hibernate.validator.internal.engine.constraintvalidation.ConstraintViolationCreationContext;
import org.hibernate.validator.internal.engine.path.PathImpl;
import org.hibernate.validator.internal.engine.valuecontext.ValueContext;
import org.hibernate.validator.internal.metadata.aggregated.BeanMetaData;
import org.hibernate.validator.internal.metadata.core.MetaConstraint;
import org.hibernate.validator.internal.metadata.descriptor.ConstraintDescriptorImpl;
import org.hibernate.validator.internal.util.logging.Log;
import org.hibernate.validator.internal.util.logging.LoggerFactory;

/**
 * Context object keeping track of all required data for a validation call.
 * <p>
 * We use this object to collect all failing constraints, but also to have access to resources like
 * constraint validator factory, message interpolator, traversable resolver, etc.
 *
 * @author Hardy Ferentschik
 * @author Emmanuel Bernard
 * @author Gunnar Morling
 * @author Guillaume Smet
 * @author Marko Bekhta
 */
abstract class AbstractValidationContext<T> implements BaseBeanValidationContext<T> {

	private static final Log LOG = LoggerFactory.make( MethodHandles.lookup() );

	/**
	 * Caches and manages life cycle of constraint validator instances.
	 */
	private final ConstraintValidatorManager constraintValidatorManager;

	/**
	 * The root bean of the validation.
	 */
	private final T rootBean;

	/**
	 * The root bean class of the validation.
	 */
	private final Class<T> rootBeanClass;

	/**
	 * The metadata of the root bean.
	 */
	private final BeanMetaData<T> rootBeanMetaData;

	/**
	 * The set of already processed meta constraints per bean - path ({@link BeanPathMetaConstraintProcessedUnit}).
	 */
	private final Set<BeanPathMetaConstraintProcessedUnit> processedPathUnits;

	/**
	 * The set of already processed groups per bean ({@link BeanGroupProcessedUnit}).
	 */
	private final Set<BeanGroupProcessedUnit> processedGroupUnits;

	/**
	 * Maps an object to a list of paths in which it has been validated. The objects are the bean instances.
	 */
	private final Map<Object, Set<PathImpl>> processedPathsPerBean;

	/**
	 * Contains all failing constraints so far.
	 */
	private final Set<ConstraintViolation<T>> failingConstraintViolations;

	/**
	 * The constraint factory which should be used in this context.
	 */
	private final ConstraintValidatorFactory constraintValidatorFactory;

	/**
	 * Context containing all {@link Validator} level helpers and configuration properties.
	 */
	protected final ValidatorScopedContext validatorScopedContext;

	/**
	 * Allows a JPA provider to decide whether a property should be validated.
	 */
	private final TraversableResolver traversableResolver;

	/**
	 * The constraint validator initialization context.
	 */
	private final HibernateConstraintValidatorInitializationContext constraintValidatorInitializationContext;

	/**
	 * Indicates if the tracking of already validated bean should be disabled.
	 */
	private final boolean disableAlreadyValidatedBeanTracking;

	protected AbstractValidationContext(
			ConstraintValidatorManager constraintValidatorManager,
			ConstraintValidatorFactory constraintValidatorFactory,
			ValidatorScopedContext validatorScopedContext,
			TraversableResolver traversableResolver,
			HibernateConstraintValidatorInitializationContext constraintValidatorInitializationContext,
			T rootBean,
			Class<T> rootBeanClass,
			BeanMetaData<T> rootBeanMetaData,
			boolean disableAlreadyValidatedBeanTracking
	) {
		this.constraintValidatorManager = constraintValidatorManager;
		this.validatorScopedContext = validatorScopedContext;
		this.constraintValidatorFactory = constraintValidatorFactory;
		this.traversableResolver = traversableResolver;
		this.constraintValidatorInitializationContext = constraintValidatorInitializationContext;

		this.rootBean = rootBean;
		this.rootBeanClass = rootBeanClass;
		this.rootBeanMetaData = rootBeanMetaData;

		this.processedGroupUnits = new HashSet<>();
		this.processedPathUnits = new HashSet<>();
		this.processedPathsPerBean = new IdentityHashMap<>();
		this.failingConstraintViolations = newHashSet();

		this.disableAlreadyValidatedBeanTracking = disableAlreadyValidatedBeanTracking;
	}

	@Override
	public T getRootBean() {
		return rootBean;
	}

	@Override
	public Class<T> getRootBeanClass() {
		return rootBeanClass;
	}

	@Override
	public BeanMetaData<T> getRootBeanMetaData() {
		return rootBeanMetaData;
	}

	@Override
	public TraversableResolver getTraversableResolver() {
		return traversableResolver;
	}

	@Override
	public boolean isFailFastModeEnabled() {
		return validatorScopedContext.isFailFast();
	}

	@Override
	public ConstraintValidatorManager getConstraintValidatorManager() {
		return constraintValidatorManager;
	}

	@Override
	public HibernateConstraintValidatorInitializationContext getConstraintValidatorInitializationContext() {
		return constraintValidatorInitializationContext;
	}

	@Override
	public ConstraintValidatorFactory getConstraintValidatorFactory() {
		return constraintValidatorFactory;
	}

	@Override
	public boolean isBeanAlreadyValidated(Object value, Class<?> group, PathImpl path) {
		if ( disableAlreadyValidatedBeanTracking ) {
			return false;
		}

		boolean alreadyValidated;
		alreadyValidated = isAlreadyValidatedForCurrentGroup( value, group );

		if ( alreadyValidated ) {
			alreadyValidated = isAlreadyValidatedForPath( value, path );
		}

		return alreadyValidated;
	}

	@Override
	public void markCurrentBeanAsProcessed(ValueContext<?, ?> valueContext) {
		if ( disableAlreadyValidatedBeanTracking ) {
			return;
		}

		markCurrentBeanAsProcessedForCurrentGroup( valueContext.getCurrentBean(), valueContext.getCurrentGroup() );
		markCurrentBeanAsProcessedForCurrentPath( valueContext.getCurrentBean(), valueContext.getPropertyPath() );
	}

	@Override
	public Set<ConstraintViolation<T>> getFailingConstraints() {
		return failingConstraintViolations;
	}

	@Override
	public void addConstraintFailure(
			ValueContext<?, ?> valueContext,
			ConstraintViolationCreationContext constraintViolationCreationContext,
			ConstraintDescriptor<?> descriptor
	) {
		String messageTemplate = constraintViolationCreationContext.getMessage();
		String interpolatedMessage = interpolate(
				messageTemplate,
				valueContext.getCurrentValidatedValue(),
				descriptor,
				constraintViolationCreationContext.getMessageParameters(),
				constraintViolationCreationContext.getExpressionVariables()
		);
		// at this point we make a copy of the path to avoid side effects
		Path path = PathImpl.createCopy( constraintViolationCreationContext.getPath() );

		this.failingConstraintViolations.add(
				createConstraintViolation(
						messageTemplate,
						interpolatedMessage,
						path,
						descriptor,
						valueContext,
						constraintViolationCreationContext
				)
		);
	}

	protected abstract ConstraintViolation<T> createConstraintViolation(
			String messageTemplate,
			String interpolatedMessage,
			Path propertyPath,
			ConstraintDescriptor<?> constraintDescriptor,
			ValueContext<?, ?> valueContext,
			ConstraintViolationCreationContext constraintViolationCreationContext);

	@Override
	public boolean hasMetaConstraintBeenProcessed(Object bean, Path path, MetaConstraint<?> metaConstraint) {
		// this is only useful if the constraint is defined for more than 1 group as in the case it's only
		// defined for one group, there is no chance it's going to be called twice.
		if ( metaConstraint.isDefinedForOneGroupOnly() ) {
			return false;
		}

		return processedPathUnits.contains( new BeanPathMetaConstraintProcessedUnit( bean, path, metaConstraint ) );
	}

	@Override
	public void markConstraintProcessed(Object bean, Path path, MetaConstraint<?> metaConstraint) {
		// this is only useful if the constraint is defined for more than 1 group as in the case it's only
		// defined for one group, there is no chance it's going to be called twice.
		if ( metaConstraint.isDefinedForOneGroupOnly() ) {
			return;
		}

		processedPathUnits.add( new BeanPathMetaConstraintProcessedUnit( bean, path, metaConstraint ) );
	}

	@Override
	public ConstraintValidatorContextImpl createConstraintValidatorContextFor(ConstraintDescriptorImpl<?> constraintDescriptor, PathImpl path) {
		return new ConstraintValidatorContextImpl(
				validatorScopedContext.getClockProvider(),
				path,
				constraintDescriptor,
				validatorScopedContext.getConstraintValidatorPayload()
		);
	}

	@Override
	public abstract String toString();

	private String interpolate(
			String messageTemplate,
			Object validatedValue,
			ConstraintDescriptor<?> descriptor,
			Map<String, Object> messageParameters,
			Map<String, Object> expressionVariables) {
		MessageInterpolatorContext context = new MessageInterpolatorContext(
				descriptor,
				validatedValue,
				getRootBeanClass(),
				messageParameters,
				expressionVariables
		);

		try {
			return validatorScopedContext.getMessageInterpolator().interpolate(
					messageTemplate,
					context
			);
		}
		catch (ValidationException ve) {
			throw ve;
		}
		catch (Exception e) {
			throw LOG.getExceptionOccurredDuringMessageInterpolationException( e );
		}
	}

	private boolean isAlreadyValidatedForPath(Object value, PathImpl path) {
		Set<PathImpl> pathSet = processedPathsPerBean.get( value );
		if ( pathSet == null ) {
			return false;
		}

		for ( PathImpl p : pathSet ) {
			if ( path.isRootPath() || p.isRootPath() || isSubPathOf( path, p ) || isSubPathOf( p, path ) ) {
				return true;
			}
		}

		return false;
	}

	private boolean isSubPathOf(Path p1, Path p2) {
		Iterator<Path.Node> p1Iter = p1.iterator();
		Iterator<Path.Node> p2Iter = p2.iterator();
		while ( p1Iter.hasNext() ) {
			Path.Node p1Node = p1Iter.next();
			if ( !p2Iter.hasNext() ) {
				return false;
			}
			Path.Node p2Node = p2Iter.next();
			if ( !p1Node.equals( p2Node ) ) {
				return false;
			}
		}
		return true;
	}

	private boolean isAlreadyValidatedForCurrentGroup(Object value, Class<?> group) {
		return processedGroupUnits.contains( new BeanGroupProcessedUnit( value, group ) );
	}

	private void markCurrentBeanAsProcessedForCurrentPath(Object bean, PathImpl path) {
		// HV-1031 The path object is mutated as we traverse the object tree, hence copy it before saving it
		processedPathsPerBean.computeIfAbsent( bean, b -> new HashSet<>() )
				.add( PathImpl.createCopy( path ) );
	}

	private void markCurrentBeanAsProcessedForCurrentGroup(Object bean, Class<?> group) {
		processedGroupUnits.add( new BeanGroupProcessedUnit( bean, group ) );
	}

	private static final class BeanPathMetaConstraintProcessedUnit {

		// these fields are final but we don't mark them as final as an optimization
		private Object bean;
		private Path path;
		private MetaConstraint<?> metaConstraint;
		private int hashCode;

		BeanPathMetaConstraintProcessedUnit(Object bean, Path path, MetaConstraint<?> metaConstraint) {
			this.bean = bean;
			this.path = path;
			this.metaConstraint = metaConstraint;
			this.hashCode = createHashCode();
		}

		@Override
		public boolean equals(Object o) {
			if ( this == o ) {
				return true;
			}

			// No need to check if the class matches because of how this class is used in the set.
			BeanPathMetaConstraintProcessedUnit that = (BeanPathMetaConstraintProcessedUnit) o;

			if ( bean != that.bean ) {  // instance equality
				return false;
			}
			if ( metaConstraint != that.metaConstraint ) {
				return false;
			}
			if ( !path.equals( that.path ) ) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		private int createHashCode() {
			int result = System.identityHashCode( bean );
			result = 31 * result + path.hashCode();
			result = 31 * result + System.identityHashCode( metaConstraint );
			return result;
		}
	}

	private static final class BeanGroupProcessedUnit {

		// these fields are final but we don't mark them as final as an optimization
		private Object bean;
		private Class<?> group;
		private int hashCode;

		BeanGroupProcessedUnit(Object bean, Class<?> group) {
			this.bean = bean;
			this.group = group;
			this.hashCode = createHashCode();
		}

		@Override
		public boolean equals(Object o) {
			if ( this == o ) {
				return true;
			}

			// No need to check if the class matches because of how this class is used in the set.
			BeanGroupProcessedUnit that = (BeanGroupProcessedUnit) o;

			if ( bean != that.bean ) {  // instance equality
				return false;
			}
			if ( !group.equals( that.group ) ) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		private int createHashCode() {
			int result = System.identityHashCode( bean );
			result = 31 * result + group.hashCode();
			return result;
		}
	}
}
