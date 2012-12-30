package net.edralzar.rdb2epub.oauth;

import org.scribe.builder.api.DefaultApi10a;
import org.scribe.model.Token;

public class ReadabilityApi extends DefaultApi10a {

	@Override
	public String getAccessTokenEndpoint() {
		return "https://www.readability.com/api/rest/v1/oauth/access_token/";
	}

	@Override
	public String getAuthorizationUrl(Token arg0) {
		return String
				.format("https://www.readability.com/api/rest/v1/oauth/authorize/?oauth_token=%s",
						arg0.getToken());
	}

	@Override
	public String getRequestTokenEndpoint() {
		return "https://www.readability.com/api/rest/v1/oauth/request_token/";
	}

}
