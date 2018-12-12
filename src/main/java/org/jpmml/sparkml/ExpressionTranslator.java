/*
 * Copyright (c) 2018 Villu Ruusmann
 *
 * This file is part of JPMML-SparkML
 *
 * JPMML-SparkML is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-SparkML is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-SparkML.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.sparkml;

import java.util.List;

import org.apache.spark.sql.catalyst.expressions.Add;
import org.apache.spark.sql.catalyst.expressions.Alias;
import org.apache.spark.sql.catalyst.expressions.And;
import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.expressions.BinaryArithmetic;
import org.apache.spark.sql.catalyst.expressions.BinaryComparison;
import org.apache.spark.sql.catalyst.expressions.BinaryOperator;
import org.apache.spark.sql.catalyst.expressions.Divide;
import org.apache.spark.sql.catalyst.expressions.EqualTo;
import org.apache.spark.sql.catalyst.expressions.Expression;
import org.apache.spark.sql.catalyst.expressions.GreaterThan;
import org.apache.spark.sql.catalyst.expressions.GreaterThanOrEqual;
import org.apache.spark.sql.catalyst.expressions.If;
import org.apache.spark.sql.catalyst.expressions.In;
import org.apache.spark.sql.catalyst.expressions.IsNotNull;
import org.apache.spark.sql.catalyst.expressions.IsNull;
import org.apache.spark.sql.catalyst.expressions.LessThan;
import org.apache.spark.sql.catalyst.expressions.LessThanOrEqual;
import org.apache.spark.sql.catalyst.expressions.Literal;
import org.apache.spark.sql.catalyst.expressions.Multiply;
import org.apache.spark.sql.catalyst.expressions.Not;
import org.apache.spark.sql.catalyst.expressions.Or;
import org.apache.spark.sql.catalyst.expressions.Subtract;
import org.apache.spark.sql.catalyst.expressions.UnaryExpression;
import org.apache.spark.sql.types.BooleanType;
import org.apache.spark.sql.types.DoubleType;
import org.apache.spark.sql.types.IntegralType;
import org.apache.spark.sql.types.StringType;
import org.dmg.pmml.Apply;
import org.dmg.pmml.DataType;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.FieldRef;
import org.jpmml.converter.PMMLUtil;
import scala.collection.JavaConversions;

public class ExpressionTranslator {

	static
	public ExpressionMapping translate(Expression expression){

		if(expression instanceof Alias){
			Alias alias = (Alias)expression;

			Expression child = alias.child();

			return translate(child);
		} // End if

		if(expression instanceof AttributeReference){
			AttributeReference attributeReference = (AttributeReference)expression;

			String name = attributeReference.name();

			DataType dataType = translateDataType(attributeReference.dataType());

			return new ExpressionMapping(attributeReference, new FieldRef(FieldName.create(name)), dataType);
		} else

		if(expression instanceof BinaryOperator){
			BinaryOperator binaryOperator = (BinaryOperator)expression;

			String symbol = binaryOperator.symbol();

			Expression left = binaryOperator.left();
			Expression right = binaryOperator.right();

			DataType dataType;

			if(expression instanceof And || expression instanceof Or){
				symbol = symbol.toLowerCase();

				dataType = DataType.BOOLEAN;
			} else

			if(expression instanceof Add || expression instanceof Divide || expression instanceof Multiply || expression instanceof Subtract){
				BinaryArithmetic binaryArithmetic = (BinaryArithmetic)binaryOperator;

				if((left.dataType()).acceptsType(right.dataType())){
					dataType = translateDataType(left.dataType());
				} else

				if((right.dataType()).acceptsType(left.dataType())){
					dataType = translateDataType(right.dataType());
				} else

				{
					throw new IllegalArgumentException(String.valueOf(binaryArithmetic));
				}
			} else

			if(expression instanceof EqualTo || expression instanceof GreaterThan || expression instanceof GreaterThanOrEqual || expression instanceof LessThan || expression instanceof LessThanOrEqual){
				BinaryComparison binaryComparison = (BinaryComparison)binaryOperator;

				switch(symbol){
					case "=":
						symbol = "equal";
						break;
					case ">":
						symbol = "greaterThan";
						break;
					case ">=":
						symbol = "greaterOrEqual";
						break;
					case "<":
						symbol = "lessThan";
						break;
					case "<=":
						symbol = "lessOrEqual";
						break;
					default:
						throw new IllegalArgumentException(String.valueOf(binaryComparison));
				}

				dataType = DataType.BOOLEAN;
			} else

			{
				throw new IllegalArgumentException(String.valueOf(binaryOperator));
			}

			return new ExpressionMapping(binaryOperator, PMMLUtil.createApply(symbol, translateChild(left), translateChild(right)), dataType);
		} else

		if(expression instanceof If){
			If _if = (If)expression;

			Expression predicate = _if.predicate();

			Expression trueValue = _if.trueValue();
			Expression falseValue = _if.falseValue();

			if(!(trueValue.dataType()).sameType(falseValue.dataType())){
				throw new IllegalArgumentException(String.valueOf(_if));
			}

			DataType dataType = translateDataType(trueValue.dataType());

			Apply apply = PMMLUtil.createApply("if", translateChild(predicate))
				.addExpressions(translateChild(trueValue), translateChild(falseValue));

			return new ExpressionMapping(_if, apply, dataType);
		} else

		if(expression instanceof In){
			In in = (In)expression;

			Expression value = in.value();

			List<Expression> elements = JavaConversions.seqAsJavaList(in.list());

			Apply apply = PMMLUtil.createApply("isIn", translateChild(value));

			for(Expression element : elements){
				apply.addExpressions(translateChild(element));
			}

			return new ExpressionMapping(in, apply, DataType.BOOLEAN);
		} else

		if(expression instanceof Literal){
			Literal literal = (Literal)expression;

			Object value = literal.value();

			DataType dataType = translateDataType(literal.dataType());

			return new ExpressionMapping(literal, PMMLUtil.createConstant(value, dataType), dataType);
		} else

		if(expression instanceof Not){
			 Not not = (Not)expression;

			 Expression child = not.child();

			 return new ExpressionMapping(not, PMMLUtil.createApply("not", translateChild(child)), DataType.BOOLEAN);
		} else

		if(expression instanceof UnaryExpression){
			UnaryExpression unaryExpression = (UnaryExpression)expression;

			Expression child = unaryExpression.child();

			if(expression instanceof IsNotNull){
				return new ExpressionMapping(unaryExpression, PMMLUtil.createApply("isNotMissing", translateChild(child)), DataType.BOOLEAN);
			} else

			if(expression instanceof IsNull){
				return new ExpressionMapping(unaryExpression, PMMLUtil.createApply("isMissing", translateChild(child)), DataType.BOOLEAN);
			} else

			{
				throw new IllegalArgumentException(String.valueOf(unaryExpression));
			}
		} else

		{
			throw new IllegalArgumentException(String.valueOf(expression));
		}
	}

	static
	private org.dmg.pmml.Expression translateChild(Expression expression){
		ExpressionMapping expressionMapping = translate(expression);

		return expressionMapping.getTo();
	}

	static
	private DataType translateDataType(org.apache.spark.sql.types.DataType sparkDataType){

		if(sparkDataType instanceof StringType){
			return DataType.STRING;
		} else

		if(sparkDataType instanceof IntegralType){
			return DataType.INTEGER;
		} else

		if(sparkDataType instanceof DoubleType){
			return DataType.DOUBLE;
		} else

		if(sparkDataType instanceof BooleanType){
			return DataType.BOOLEAN;
		} else

		{
			throw new IllegalArgumentException("Expected string, integral, double or boolean type, got " + sparkDataType.typeName() + " type");
		}
	}
}