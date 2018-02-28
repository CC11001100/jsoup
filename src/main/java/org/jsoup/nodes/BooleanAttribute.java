package org.jsoup.nodes;

/**
 * 布尔属性值，已弃用，当前使用null或空串表示即可
 * A boolean attribute that is written out without any value.
 *
 * @deprecated just use null values (vs empty string) for booleans.
 */
@Deprecated
public class BooleanAttribute extends Attribute {

	/**
	 * Create a new boolean attribute from unencoded (raw) key.
	 *
	 * @param key attribute key
	 */
	public BooleanAttribute(String key) {
		super(key, null);
	}

	@Override
	protected boolean isBooleanAttribute() {
		return true;
	}
}
