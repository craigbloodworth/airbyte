#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#
from typing import Any, Mapping, MutableMapping, Optional, Union

import requests
from airbyte_cdk.sources.declarative.decoders.decoder import Decoder
from airbyte_cdk.sources.declarative.interpolation.interpolated_string import InterpolatedString
from airbyte_cdk.sources.declarative.requesters.request_params.request_parameters_provider import RequestParameterProvider
from airbyte_cdk.sources.declarative.requesters.requester import HttpMethod, Requester
from airbyte_cdk.sources.declarative.requesters.retriers.retrier import Retrier
from airbyte_cdk.sources.declarative.response import Response
from airbyte_cdk.sources.declarative.types import Config
from airbyte_cdk.sources.streams.http.auth import HttpAuthenticator


class HttpRequester(Requester):
    def __init__(
        self,
        *,
        name: str,
        url_base: [str, InterpolatedString],
        path: [str, InterpolatedString],
        http_method: Union[str, HttpMethod],
        request_parameters_provider: RequestParameterProvider,
        authenticator: HttpAuthenticator,
        decoder: Decoder,
        retrier: Retrier,
        config: Config,
    ):
        self._name = name
        self._authenticator = authenticator
        if type(url_base) == str:
            url_base = InterpolatedString(url_base)
        self._url_base = url_base
        if type(path) == str:
            path = InterpolatedString(path)
        self._path: InterpolatedString = path
        if type(http_method) == str:
            http_method = HttpMethod[http_method]
        self._method = http_method
        self._request_parameters_provider = request_parameters_provider
        self._decoder = decoder
        self._retrier = retrier
        self._config = config

    def parse_response(self, response: Any) -> Response:
        return self._decoder.decode(response)

    def request_params(
        self, stream_state: Mapping[str, Any], stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> MutableMapping[str, Any]:
        return self._request_parameters_provider.request_params(stream_state, stream_slice, next_page_token)

    def get_authenticator(self):
        return self._authenticator

    def get_url_base(self):
        return self._url_base.eval(self._config)

    def get_path(self, *, stream_state: Mapping[str, Any], stream_slice: Mapping[str, Any], next_page_token: Mapping[str, Any]) -> str:
        kwargs = {"stream_state": stream_state, "stream_slice": stream_slice, "next_page_token": next_page_token}
        path = self._path.eval(self._config, **kwargs)
        return path

    def get_method(self):
        return self._method

    @property
    def raise_on_http_errors(self) -> bool:
        # TODO this should be declarative
        return True

    @property
    def max_retries(self) -> Union[int, None]:
        return self._retrier.max_retries

    @property
    def retry_factor(self) -> float:
        return self._retrier.retry_factor

    def should_retry(self, response: requests.Response) -> bool:
        return self._retrier.should_retry(response)

    def backoff_time(self, response: requests.Response) -> Optional[float]:
        return self._retrier.backoff_time(response)

    def request_headers(
        self, stream_state: Mapping[str, Any], stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> Mapping[str, Any]:
        # FIXME: this should be declarative
        return dict()

    def request_body_data(
        self, stream_state: Mapping[str, Any], stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> Optional[Union[Mapping, str]]:
        # FIXME: this should be declarative
        return dict()

    def request_body_json(
        self, stream_state: Mapping[str, Any], stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> Optional[Mapping]:
        # FIXME: this should be declarative
        return dict()

    def request_kwargs(
        self, stream_state: Mapping[str, Any], stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> Mapping[str, Any]:
        # FIXME: this should be declarative
        return dict()

    @property
    def cache_filename(self) -> str:
        # FIXME: this should be declarative
        return f"{self._name}.yml"

    @property
    def use_cache(self) -> bool:
        # FIXME: this should be declarative
        return False
